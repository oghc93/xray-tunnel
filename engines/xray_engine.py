"""
Xray Engine
Jalankan xray-core binary untuk VMess/VLess/Trojan/SS
"""
import subprocess
import threading
import json
import time
import os
import uuid

XRAY_BIN = os.path.join(os.path.dirname(__file__), '..', 'assets', 'xray')


class XrayEngine:
    def __init__(self):
        self.proc        = None
        self.is_running  = False
        self.on_status   = None   # callback(msg: str)
        self.on_log      = None   # callback(line: str)
        self._stop_evt   = threading.Event()
        self.local_port  = 10808  # SOCKS5 port lokal
        self.http_port   = 10809  # HTTP proxy port lokal

    def connect(self, profile: dict):
        """
        profile keys:
          proto       : vmess | vless | trojan | ss
          address     : host server
          port        : port server
          uuid        : UUID / password
          alter_id    : 0 (VMess)
          network     : ws | tcp | grpc
          path        : WebSocket path
          tls         : true | false
          sni         : SNI host
          fingerprint : chrome | firefox | safari | ''
          flow        : '' | xtls-rprx-vision
          method      : aes-128-gcm (SS)
        """
        self._stop_evt.clear()
        threading.Thread(target=self._run, args=(profile,),
                         daemon=True).start()

    def disconnect(self):
        self._stop_evt.set()
        if self.proc:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self.is_running = False
        self._notify('Xray berhenti')

    # ── Config builder ──────────────────────────────
    def build_config(self, profile: dict) -> dict:
        proto   = profile.get('proto', 'vmess').lower()
        addr    = profile.get('address', '')
        port    = int(profile.get('port', 443))
        uid     = profile.get('uuid', str(uuid.uuid4()))
        network = profile.get('network', 'ws')
        path    = profile.get('path', '/')
        tls     = profile.get('tls', True)
        sni     = profile.get('sni', addr)
        fp      = profile.get('fingerprint', 'chrome')

        # ─ Stream settings
        ws_settings = {
            'path': path,
            'headers': {'Host': sni or addr},
        }
        grpc_settings = {
            'serviceName': profile.get('service_name', ''),
            'multiMode': False,
        }
        tls_settings = {
            'serverName': sni,
            'fingerprint': fp,
            'allowInsecure': profile.get('allow_insecure', False),
        }
        stream = {
            'network': network,
            'security': 'tls' if tls else 'none',
        }
        if network == 'ws':
            stream['wsSettings'] = ws_settings
        elif network == 'grpc':
            stream['grpcSettings'] = grpc_settings
        if tls:
            stream['tlsSettings'] = tls_settings

        # ─ Outbound
        if proto == 'vmess':
            outbound_settings = {
                'vnext': [{
                    'address': addr, 'port': port,
                    'users': [{'id': uid, 'alterId': int(profile.get('alter_id', 0)),
                               'security': 'auto'}]
                }]
            }
            out_proto = 'vmess'
        elif proto == 'vless':
            outbound_settings = {
                'vnext': [{
                    'address': addr, 'port': port,
                    'users': [{'id': uid, 'encryption': 'none',
                               'flow': profile.get('flow', '')}]
                }]
            }
            out_proto = 'vless'
        elif proto == 'trojan':
            outbound_settings = {
                'servers': [{
                    'address': addr, 'port': port,
                    'password': uid,
                }]
            }
            out_proto = 'trojan'
        elif proto == 'ss':
            outbound_settings = {
                'servers': [{
                    'address': addr, 'port': port,
                    'method': profile.get('method', 'aes-128-gcm'),
                    'password': uid,
                }]
            }
            out_proto = 'shadowsocks'
        else:
            raise ValueError(f'Protokol tidak didukung: {proto}')

        config = {
            'log': {'loglevel': 'warning'},
            'inbounds': [
                {
                    'tag': 'socks',
                    'port': self.local_port,
                    'listen': '127.0.0.1',
                    'protocol': 'socks',
                    'settings': {'auth': 'noauth', 'udp': True},
                },
                {
                    'tag': 'http',
                    'port': self.http_port,
                    'listen': '127.0.0.1',
                    'protocol': 'http',
                    'settings': {'allowTransparent': False},
                },
            ],
            'outbounds': [
                {
                    'tag': 'proxy',
                    'protocol': out_proto,
                    'settings': outbound_settings,
                    'streamSettings': stream,
                    'mux': {'enabled': profile.get('mux', False), 'concurrency': 8},
                },
                {'tag': 'direct', 'protocol': 'freedom'},
                {'tag': 'block',  'protocol': 'blackhole'},
            ],
            'routing': {
                'domainStrategy': 'IPIfNonMatch',
                'rules': [
                    {'type': 'field', 'ip': ['geoip:private'], 'outboundTag': 'direct'},
                    {'type': 'field', 'ip': ['geoip:cn'],      'outboundTag': 'direct'},
                ],
            },
            'dns': {
                'servers': ['1.1.1.1', '8.8.8.8', 'localhost'],
            },
        }
        return config

    # ── Runner ─────────────────────────────────────
    def _run(self, profile):
        try:
            self._notify('Membangun config Xray...')
            config = self.build_config(profile)

            config_path = '/tmp/xray_config.json'
            with open(config_path, 'w') as f:
                json.dump(config, f)

            xray_bin = XRAY_BIN
            if not os.path.exists(xray_bin):
                # fallback: cari di PATH
                import shutil
                xray_bin = shutil.which('xray') or ''
            if not xray_bin:
                raise FileNotFoundError(
                    'xray binary tidak ditemukan. '
                    'Taruh file xray ARM64 di assets/xray')

            # chmod +x
            os.chmod(xray_bin, 0o755)

            self._notify('Memulai Xray...')
            self.proc = subprocess.Popen(
                [xray_bin, 'run', '-config', config_path],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            self.is_running = True
            self._notify(
                f'Xray jalan ✅  '
                f'SOCKS5: 127.0.0.1:{self.local_port}  '
                f'HTTP: 127.0.0.1:{self.http_port}')

            # Baca log
            for line in self.proc.stdout:
                if self._stop_evt.is_set():
                    break
                line = line.rstrip()
                if self.on_log:
                    self.on_log(line)
                if 'started' in line.lower():
                    self._notify('Xray siap ✅')

            self.proc.wait()
            self.is_running = False
            if not self._stop_evt.is_set():
                self._notify('Xray berhenti tidak terduga ⚠️')

        except Exception as e:
            self.is_running = False
            self._notify(f'Xray error: {e}')

    def _notify(self, msg):
        if self.on_status:
            self.on_status(msg)

    @staticmethod
    def parse_link(link: str) -> dict:
        """Parse vmess:// / vless:// / trojan:// link jadi dict profile."""
        import base64, urllib.parse
        link = link.strip()
        if link.startswith('vmess://'):
            b64 = link[8:]
            pad = 4 - len(b64) % 4
            d   = json.loads(base64.urlsafe_b64decode(b64 + '=' * pad))
            return {
                'proto': 'vmess', 'name': d.get('ps', 'VMess'),
                'address': d.get('add', ''), 'port': int(d.get('port', 443)),
                'uuid': d.get('id', ''), 'alter_id': int(d.get('aid', 0)),
                'network': d.get('net', 'ws'), 'path': d.get('path', '/'),
                'sni': d.get('sni', d.get('host', '')),
                'tls': d.get('tls', '') == 'tls',
            }
        elif link.startswith('vless://'):
            p = urllib.parse.urlparse(link)
            q = urllib.parse.parse_qs(p.query)
            return {
                'proto': 'vless', 'name': urllib.parse.unquote(p.fragment or 'VLess'),
                'address': p.hostname, 'port': p.port or 443,
                'uuid': p.username, 'network': q.get('type', ['ws'])[0],
                'path': q.get('path', ['/'])[0],
                'sni': q.get('sni', [p.hostname])[0],
                'tls': q.get('security', ['none'])[0] == 'tls',
                'flow': q.get('flow', [''])[0],
                'fingerprint': q.get('fp', ['chrome'])[0],
            }
        elif link.startswith('trojan://'):
            p = urllib.parse.urlparse(link)
            q = urllib.parse.parse_qs(p.query)
            return {
                'proto': 'trojan', 'name': urllib.parse.unquote(p.fragment or 'Trojan'),
                'address': p.hostname, 'port': p.port or 443,
                'uuid': p.username, 'network': q.get('type', ['tcp'])[0],
                'path': q.get('path', ['/'])[0],
                'sni': q.get('sni', [p.hostname])[0],
                'tls': True,
            }
        else:
            raise ValueError('Format link tidak dikenal')
