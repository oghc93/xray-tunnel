from kivymd.uix.screen import MDScreen
from kivymd.uix.boxlayout import MDBoxLayout
from kivymd.uix.label import MDLabel
from kivymd.uix.button import MDRaisedButton, MDFlatButton
from kivymd.uix.card import MDCard
from kivymd.uix.snackbar import Snackbar
from kivymd.uix.scrollview import MDScrollView
from kivy.clock import Clock
from kivy.metrics import dp
import threading


class HomeScreen(MDScreen):
    def __init__(self, **kw):
        super().__init__(**kw)
        self.name = 'home'
        self.engine = None
        self.active_profile = None
        self._build()
        Clock.schedule_interval(self._tick, 1)

    def _build(self):
        root = MDBoxLayout(orientation='vertical')

        # --- Top status panel ---
        top = MDCard(
            orientation='vertical', padding=dp(24), spacing=dp(10),
            md_bg_color=(0.09, 0.09, 0.16, 1),
            size_hint_y=None, height=dp(230),
            radius=[0, 0, 28, 28], elevation=4)

        self.status_lbl = MDLabel(
            text='TIDAK TERHUBUNG', font_style='Caption', bold=True,
            theme_text_color='Custom', text_color=(0.5, 0.5, 0.6, 1),
            halign='center', size_hint_y=None, height=dp(22))

        self.conn_btn = MDRaisedButton(
            text='HUBUNGKAN', font_size='15sp', bold=True,
            size_hint=(None, None), size=(dp(150), dp(150)),
            pos_hint={'center_x': .5},
            md_bg_color=(0.22, 0.22, 0.38, 1), elevation=8)
        self.conn_btn.bind(on_release=self._toggle)

        self.uptime_lbl = MDLabel(
            text='00:00:00', font_style='H6', bold=True,
            theme_text_color='Custom', text_color=(0.4, 0.4, 0.5, 1),
            halign='center', size_hint_y=None, height=dp(30))

        top.add_widget(self.status_lbl)
        top.add_widget(self.conn_btn)
        top.add_widget(self.uptime_lbl)
        root.add_widget(top)

        # --- Speed stats ---
        stats = MDBoxLayout(
            size_hint_y=None, height=dp(70),
            padding=[dp(12), dp(8)], spacing=dp(8))
        self.dl_card   = self._scard('Download', '0 B/s',  (0.0, 0.78, 0.56, 1))
        self.ul_card   = self._scard('Upload',   '0 B/s',  (0.47, 0.44, 0.97, 1))
        self.ping_card = self._scard('Ping',     '-- ms',  (1.0, 0.43, 0.27, 1))
        for c in [self.dl_card, self.ul_card, self.ping_card]:
            stats.add_widget(c)
        root.add_widget(stats)

        # --- Scroll area ---
        sv = MDScrollView()
        inner = MDBoxLayout(
            orientation='vertical', spacing=dp(10),
            padding=[dp(14), dp(4)],
            size_hint_y=None, adaptive_height=True)

        # Active profile
        ph = MDBoxLayout(size_hint_y=None, height=dp(36))
        ph.add_widget(MDLabel(text='Profil Aktif', font_style='Subtitle1', bold=True))
        cb = MDFlatButton(text='GANTI', size_hint_x=None, width=dp(70))
        cb.bind(on_release=lambda x: self._goto('profiles'))
        ph.add_widget(cb)
        inner.add_widget(ph)

        self.prof_card = MDCard(
            padding=dp(14), radius=[12], elevation=1,
            md_bg_color=(0.12, 0.12, 0.2, 1),
            size_hint_y=None, adaptive_height=True)
        self.prof_lbl = MDLabel(
            text='Belum ada profil. Buka tab Profil untuk menambah.',
            font_style='Body2', theme_text_color='Secondary',
            size_hint_y=None, adaptive_height=True)
        self.prof_card.add_widget(self.prof_lbl)
        inner.add_widget(self.prof_card)

        # Log
        inner.add_widget(MDLabel(
            text='Log Koneksi', font_style='Subtitle1', bold=True,
            size_hint_y=None, height=dp(32)))
        log_wrap = MDCard(
            padding=dp(10), radius=[10], elevation=1,
            md_bg_color=(0.07, 0.07, 0.12, 1),
            size_hint_y=None, height=dp(160))
        log_sv = MDScrollView()
        self.log_lbl = MDLabel(
            text='Menunggu koneksi...', font_style='Caption',
            theme_text_color='Secondary',
            size_hint_y=None, adaptive_height=True)
        log_sv.add_widget(self.log_lbl)
        log_wrap.add_widget(log_sv)
        inner.add_widget(log_wrap)

        sv.add_widget(inner)
        root.add_widget(sv)
        self.add_widget(root)

    def _scard(self, label, val, color):
        c = MDCard(orientation='vertical', padding=dp(8),
                   radius=[10], elevation=1,
                   md_bg_color=(0.1, 0.1, 0.18, 1))
        c.add_widget(MDLabel(
            text=label, font_style='Caption',
            theme_text_color='Custom', text_color=color, halign='center'))
        v = MDLabel(text=val, font_style='Body2', bold=True,
                    halign='center', theme_text_color='Custom',
                    text_color=(1, 1, 1, .9))
        c.val = v
        c.add_widget(v)
        return c

    def on_enter(self):
        self._load_profile()

    def _load_profile(self):
        from profile_store import list_profiles
        profiles = list_profiles()
        if profiles:
            self.active_profile = profiles[0]
            p = profiles[0]
            h = p.get('host') or p.get('address', '')
            self.prof_lbl.text = (
                f"{p.get('name','Profil')}  "
                f"[{p.get('type','xray').upper()}]\n{h}:{p.get('port','')}")
        else:
            self.active_profile = None
            self.prof_lbl.text = 'Belum ada profil. Tambah di tab Profil.'

    def _toggle(self, *a):
        connected = False
        if self.engine:
            connected = (getattr(self.engine, 'is_connected', False)
                         or getattr(self.engine, 'is_running', False))
        if connected:
            self._disconnect()
        else:
            self._connect()

    def _connect(self):
        if not self.active_profile:
            Snackbar(text='Pilih profil dulu!',
                     snackbar_x=dp(10), snackbar_y=dp(10)).open()
            return
        p = self.active_profile
        if p.get('type') == 'sshws':
            from engines.ssh_ws import SshWsEngine
            self.engine = SshWsEngine()
            self.engine.on_status = self._on_status
            self.engine.on_stats  = self._on_stats
        else:
            from engines.xray_engine import XrayEngine
            self.engine = XrayEngine()
            self.engine.on_status = self._on_status
            self.engine.on_log    = self._on_log
        threading.Thread(target=self.engine.connect,
                         args=(p,), daemon=True).start()
        self._ui_connecting()

    def _disconnect(self):
        if self.engine:
            threading.Thread(target=self.engine.disconnect, daemon=True).start()
        self._ui_off()

    def _ui_connecting(self):
        self.conn_btn.text = 'PUTUSKAN'
        self.conn_btn.md_bg_color = (0.8, 0.2, 0.2, 1)
        self.status_lbl.text = 'MENGHUBUNGKAN...'
        self.status_lbl.text_color = (1.0, 0.8, 0.2, 1)

    def _ui_on(self):
        self.conn_btn.md_bg_color = (0.1, 0.65, 0.4, 1)
        self.status_lbl.text = 'TERHUBUNG'
        self.status_lbl.text_color = (0.1, 0.9, 0.55, 1)
        self.uptime_lbl.text_color = (0.1, 0.9, 0.55, 1)

    def _ui_off(self):
        self.conn_btn.text = 'HUBUNGKAN'
        self.conn_btn.md_bg_color = (0.22, 0.22, 0.38, 1)
        self.status_lbl.text = 'TIDAK TERHUBUNG'
        self.status_lbl.text_color = (0.5, 0.5, 0.6, 1)
        self.uptime_lbl.text = '00:00:00'
        self.uptime_lbl.text_color = (0.4, 0.4, 0.5, 1)
        for c in [self.dl_card, self.ul_card, self.ping_card]:
            c.val.text = '0 B/s' if 'ing' in c.children[1].text else '-- ms'

    def _on_status(self, msg):
        Clock.schedule_once(lambda dt: self._handle_status(msg))

    def _handle_status(self, msg):
        self._append_log(msg)
        lo = msg.lower()
        if ('terhubung' in lo or 'siap' in lo) and 'tidak' not in lo:
            self._ui_on()
        elif 'gagal' in lo or 'error' in lo or 'berhenti' in lo or 'terputus' in lo:
            self._ui_off()

    def _on_log(self, line):
        Clock.schedule_once(lambda dt: self._append_log(line))

    def _append_log(self, line):
        lines = (self.log_lbl.text + '\n' + line).split('\n')
        self.log_lbl.text = '\n'.join(lines[-25:])

    def _on_stats(self, up, dn, ping):
        Clock.schedule_once(lambda dt: self._show_stats(up, dn, ping))

    def _show_stats(self, up, dn, ping):
        def fmt(b):
            if b < 1024: return f'{b} B/s'
            if b < 1024**2: return f'{b/1024:.0f} KB/s'
            return f'{b/1048576:.2f} MB/s'
        self.dl_card.val.text   = fmt(dn)
        self.ul_card.val.text   = fmt(up)
        self.ping_card.val.text = f'{ping} ms' if ping >= 0 else '-- ms'

    def _tick(self, dt):
        if self.engine and hasattr(self.engine, 'get_uptime'):
            self.uptime_lbl.text = self.engine.get_uptime()

    def _goto(self, screen):
        if self.manager:
            self.manager.current = screen
