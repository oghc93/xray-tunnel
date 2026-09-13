from kivymd.uix.screen import MDScreen
from kivymd.uix.boxlayout import MDBoxLayout
from kivymd.uix.label import MDLabel
from kivymd.uix.button import MDRaisedButton, MDFlatButton
from kivymd.uix.card import MDCard
from kivymd.uix.textfield import MDTextField
from kivymd.uix.scrollview import MDScrollView
from kivymd.uix.snackbar import Snackbar
from kivy.metrics import dp
from kivy.storage.jsonstore import JsonStore

PRESETS = [
    ('HTTP GET Default',
     'GET / HTTP/1.1[cr]Host: [host][cr]Connection: Upgrade[cr]Upgrade: websocket[cr][cr]'),
    ('HTTP CONNECT',
     'CONNECT [host]:443 HTTP/1.1[cr]Host: [host][cr][cr]'),
    ('WebSocket Upgrade',
     'GET / HTTP/1.1[cr]Host: [host][cr]Upgrade: websocket[cr]Connection: Upgrade[cr]'
     'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==[cr]Sec-WebSocket-Version: 13[cr][cr]'),
    ('Custom SNI + Host',
     'GET / HTTP/1.1[cr]Host: [sni][cr]X-Forward-Host: [host][cr][cr]'),
]


class PayloadScreen(MDScreen):
    def __init__(self, **kw):
        super().__init__(**kw)
        self.name = 'payload'
        self.store = JsonStore('vpn_payload.json')
        self._build()
        self._load()

    def _build(self):
        root = MDBoxLayout(orientation='vertical', spacing=dp(10),
                           padding=[dp(14), dp(8)])
        root.add_widget(MDLabel(
            text='Payload & Injeksi', font_style='H6', bold=True,
            size_hint_y=None, height=dp(40)))

        sv = MDScrollView()
        inner = MDBoxLayout(orientation='vertical', spacing=dp(12),
                            size_hint_y=None, adaptive_height=True)

        # ─ Payload editor
        payload_card = MDCard(
            orientation='vertical', padding=dp(14), spacing=dp(8),
            radius=[12], elevation=1,
            md_bg_color=(0.1, 0.1, 0.18, 1),
            size_hint_y=None, adaptive_height=True)
        payload_card.add_widget(MDLabel(
            text='📝 Payload HTTP', font_style='Subtitle1', bold=True,
            size_hint_y=None, height=dp(30)))
        payload_card.add_widget(MDLabel(
            text='Tag: [host] = server host, [sni] = SNI, [cr] = newline',
            font_style='Caption', theme_text_color='Secondary',
            size_hint_y=None, height=dp(20)))
        self.payload_field = MDTextField(
            hint_text='Payload...',
            mode='rectangle', multiline=True,
            size_hint_y=None, height=dp(130))
        payload_card.add_widget(self.payload_field)
        inner.add_widget(payload_card)

        # ─ Presets
        pre_card = MDCard(
            orientation='vertical', padding=dp(14), spacing=dp(6),
            radius=[12], elevation=1,
            md_bg_color=(0.1, 0.1, 0.18, 1),
            size_hint_y=None, adaptive_height=True)
        pre_card.add_widget(MDLabel(
            text='⚡ Preset Payload', font_style='Subtitle1', bold=True,
            size_hint_y=None, height=dp(30)))
        for name, val in PRESETS:
            row = MDBoxLayout(size_hint_y=None, height=dp(38), spacing=dp(8))
            row.add_widget(MDLabel(text=name, font_style='Body2'))
            btn = MDFlatButton(text='PAKAI', size_hint_x=None, width=dp(70))
            btn.bind(on_release=lambda x, v=val: setattr(
                self.payload_field, 'text', v))
            row.add_widget(btn)
            pre_card.add_widget(row)
        inner.add_widget(pre_card)

        # ─ SNI & custom headers
        sni_card = MDCard(
            orientation='vertical', padding=dp(14), spacing=dp(8),
            radius=[12], elevation=1,
            md_bg_color=(0.1, 0.1, 0.18, 1),
            size_hint_y=None, adaptive_height=True)
        sni_card.add_widget(MDLabel(
            text='🌐 SNI & Header Tambahan', font_style='Subtitle1', bold=True,
            size_hint_y=None, height=dp(30)))
        self.sni_field = MDTextField(
            hint_text='SNI override (kosong = pakai host)',
            mode='rectangle', size_hint_y=None, height=dp(48))
        self.header_field = MDTextField(
            hint_text='Extra headers (Key: Value, satu per baris)',
            mode='rectangle', multiline=True,
            size_hint_y=None, height=dp(90))
        sni_card.add_widget(self.sni_field)
        sni_card.add_widget(self.header_field)
        inner.add_widget(sni_card)

        # ─ DNS & proxy
        dns_card = MDCard(
            orientation='vertical', padding=dp(14), spacing=dp(8),
            radius=[12], elevation=1,
            md_bg_color=(0.1, 0.1, 0.18, 1),
            size_hint_y=None, adaptive_height=True)
        dns_card.add_widget(MDLabel(
            text='🗒️ DNS & Port Lokal', font_style='Subtitle1', bold=True,
            size_hint_y=None, height=dp(30)))
        self.dns_field = MDTextField(
            hint_text='DNS (default: 1.1.1.1)',
            text='1.1.1.1', mode='rectangle',
            size_hint_y=None, height=dp(48))
        self.socks_port = MDTextField(
            hint_text='SOCKS5 Port lokal (default: 1080)',
            text='1080', mode='rectangle',
            size_hint_y=None, height=dp(48))
        self.http_port = MDTextField(
            hint_text='HTTP Proxy Port lokal (default: 8080)',
            text='8080', mode='rectangle',
            size_hint_y=None, height=dp(48))
        for f in [self.dns_field, self.socks_port, self.http_port]:
            dns_card.add_widget(f)
        inner.add_widget(dns_card)

        # Save button
        save_btn = MDRaisedButton(
            text='SIMPAN PENGATURAN',
            size_hint_y=None, height=dp(48))
        save_btn.bind(on_release=lambda x: self._save())
        inner.add_widget(save_btn)

        sv.add_widget(inner)
        root.add_widget(sv)
        self.add_widget(root)

    def _load(self):
        if self.store.exists('payload'):
            d = self.store.get('payload')
            self.payload_field.text = d.get('payload', '')
            self.sni_field.text     = d.get('sni', '')
            self.header_field.text  = d.get('headers', '')
            self.dns_field.text     = d.get('dns', '1.1.1.1')
            self.socks_port.text    = str(d.get('socks_port', 1080))
            self.http_port.text     = str(d.get('http_port', 8080))

    def _save(self):
        self.store.put('payload',
            payload    = self.payload_field.text,
            sni        = self.sni_field.text.strip(),
            headers    = self.header_field.text,
            dns        = self.dns_field.text.strip(),
            socks_port = int(self.socks_port.text or '1080'),
            http_port  = int(self.http_port.text or '8080'),
        )
        Snackbar(text='Pengaturan disimpan!',
                 snackbar_x=dp(10), snackbar_y=dp(10)).open()

    def get_settings(self) -> dict:
        if self.store.exists('payload'):
            return self.store.get('payload')
        return {}
