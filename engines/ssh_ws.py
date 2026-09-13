import threading, socket, time
try:
    import paramiko
except ImportError:
    paramiko = None
try:
    import websocket
except ImportError:
    websocket = None

class SshWsEngine:
    def __init__(self):
        self.ws=None; self.transport=None
        self.is_connected=False; self.start_time=None
        self._stop=threading.Event()
        self.on_status=None; self.on_stats=None
        self._up=0; self._down=0

    def connect(self, profile):
        self._stop.clear()
        threading.Thread(target=self._run, args=(profile,), daemon=True).start()

    def disconnect(self):
        self._stop.set(); self.is_connected=False
        try:
            if self.transport: self.transport.close()
            if self.ws: self.ws.close()
        except: pass
        self._notify('Terputus')

    def get_uptime(self):
        if not self.start_time: return '00:00:00'
        s=int(time.time()-self.start_time)
        return f'{s//3600:02d}:{(s%3600)//60:02d}:{s%60:02d}'

    def _notify(self, msg):
        if self.on_status: self.on_status(msg)

    def _run(self, p):
        try:
            if websocket is None: raise ImportError('websocket-client tidak tersedia')
            if paramiko is None: raise ImportError('paramiko tidak tersedia')
            host=p.get('host',''); port=int(p.get('port',80))
            path=p.get('ws_path','/'); payload=p.get('payload','')
            sni=p.get('sni',host); use_tls=p.get('use_tls',False)
            scheme='wss' if use_tls else 'ws'
            headers=self._build_headers(payload,host,sni)
            self._notify('Menghubungkan WebSocket...')
            self.ws=websocket.create_connection(
                f'{scheme}://{host}:{port}{path}',
                header=headers, host=sni, timeout=10,
                skip_utf8_validation=True)
            self._notify('WS terhubung. Memulai SSH...')
            ws_sock=_WsSock(self.ws)
            self.transport=paramiko.Transport(ws_sock)
            self.transport.start_client(timeout=10)
            self.transport.auth_password(p.get('username',''),p.get('password',''),fallback=False)
            if not self.transport.is_authenticated(): raise Exception('Autentikasi gagal')
            self._start_socks5(p.get('local_port',1080))
            self.is_connected=True; self.start_time=time.time()
            self._notify('Terhubung ✅')
            self._stats_loop()
        except Exception as e:
            self.is_connected=False; self._notify(f'Gagal: {e}')

    def _build_headers(self, payload, host, sni):
        if not payload: return [f'Host: {sni or host}']
        lines=payload.replace('[host]',host).replace('[sni]',sni).replace('[cr]','\r').splitlines()
        return [l for l in lines if ':' in l] or [f'Host: {sni or host}']

    def _start_socks5(self, local_port):
        import select
        transport=self.transport
        def handle(conn,addr):
            try:
                conn.recv(256); conn.send(b'\x05\x00')
                data=conn.recv(256)
                if len(data)<7 or data[1]!=0x01: conn.close(); return
                atyp=data[3]
                if atyp==0x01: h=socket.inet_ntoa(data[4:8]); pt=int.from_bytes(data[8:10],'big')
                elif atyp==0x03: n=data[4]; h=data[5:5+n].decode(); pt=int.from_bytes(data[5+n:7+n],'big')
                else: conn.close(); return
                chan=transport.open_channel('direct-tcpip',(h,pt),('127.0.0.1',local_port))
                conn.send(b'\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00')
                while not self._stop.is_set():
                    r,_,_=select.select([conn,chan],[],[],1)
                    if conn in r:
                        d=conn.recv(4096)
                        if not d: break
                        chan.send(d); self._up+=len(d)
                    if chan in r:
                        d=chan.recv(4096)
                        if not d: break
                        conn.send(d); self._down+=len(d)
                chan.close()
            except: pass
            finally: conn.close()
        def srv():
            s=socket.socket(); s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            s.bind(('127.0.0.1',local_port)); s.listen(20); s.settimeout(1)
            while not self._stop.is_set():
                try: c,a=s.accept(); threading.Thread(target=handle,args=(c,a),daemon=True).start()
                except socket.timeout: continue
            s.close()
        threading.Thread(target=srv,daemon=True).start()

    def _stats_loop(self):
        pu=pd=0
        while not self._stop.is_set() and self.is_connected:
            time.sleep(1)
            us=self._up-pu; ds=self._down-pd; pu,pd=self._up,self._down
            try: self.transport.send_ignore(b'x'); ping=0
            except: ping=-1; self.is_connected=False
            if self.on_stats: self.on_stats(us,ds,ping)

class _WsSock:
    def __init__(self,ws): self._ws=ws; self._buf=b''
    def send(self,d): self._ws.send_binary(d); return len(d)
    def recv(self,n):
        while len(self._buf)<n:
            f=self._ws.recv()
            self._buf+=(f.encode() if isinstance(f,str) else f)
        out,self._buf=self._buf[:n],self._buf[n:]; return out
    def close(self): self._ws.close()
    def fileno(self): return self._ws.sock.fileno()
    def setblocking(self,f): self._ws.sock.setblocking(f)
    def gettimeout(self): return self._ws.sock.gettimeout()
    def settimeout(self,t): self._ws.sock.settimeout(t)
