import json, os, uuid

STORE_PATH = os.path.expanduser('~/.vpn_profiles.json')

def _load():
    if not os.path.exists(STORE_PATH): return []
    try:
        with open(STORE_PATH) as f: return json.load(f)
    except: return []

def _save(p):
    with open(STORE_PATH,'w') as f: json.dump(p,f,indent=2)

def list_profiles(): return _load()

def save_profile(p):
    all_p = _load()
    if not p.get('id'): p['id'] = str(uuid.uuid4())[:8]
    idx = [i for i,x in enumerate(all_p) if x.get('id')==p['id']]
    if idx: all_p[idx[0]] = p
    else: all_p.append(p)
    _save(all_p); return p

def delete_profile(pid):
    _save([p for p in _load() if p.get('id')!=pid])

def get_profile(pid):
    return next((p for p in _load() if p.get('id')==pid), None)

PROTO_COLORS = {
    'vmess': (0.47,0.44,0.97,1), 'vless': (0.0,0.78,0.56,1),
    'trojan':(1.0,0.43,0.27,1),  'ss':    (0.8,0.43,0.97,1),
    'sshws': (0.22,0.67,1.0,1),
}

DEFAULT_PROFILES = [
    {
        'id': 'demo-ssh',
        'name': 'Demo SSH-WS',
        'type': 'sshws',
        'host': 'your-server.com',
        'port': 80,
        'username': 'user',
        'password': 'pass',
        'ws_path': '/',
        'local_port': 1080,
    },
    {
        'id': 'demo-vmess',
        'name': 'Demo VMess WS',
        'type': 'xray',
        'proto': 'vmess',
        'address': 'your-server.com',
        'port': 443,
        'uuid': '00000000-0000-0000-0000-000000000000',
        'alter_id': 0,
        'network': 'ws',
        'path': '/vmess',
        'tls': True,
        'sni': 'your-server.com',
        'fingerprint': 'chrome',
    },
]
