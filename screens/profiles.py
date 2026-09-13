from kivymd.uix.screen import MDScreen
from kivymd.uix.boxlayout import MDBoxLayout
from kivymd.uix.label import MDLabel
from kivymd.uix.button import MDRaisedButton, MDFlatButton
from kivymd.uix.card import MDCard
from kivymd.uix.textfield import MDTextField
from kivymd.uix.dialog import MDDialog
from kivymd.uix.scrollview import MDScrollView
from kivymd.uix.snackbar import Snackbar
from kivy.metrics import dp
from kivy.clock import Clock
from profile_store import list_profiles, save_profile, delete_profile, PROTO_COLORS
from engines.xray_engine import XrayEngine


TYPE_COLORS = {
    'sshws': (0.22, 0.67, 1.0, 1),
    'xray':  (0.47, 0.44, 0.97, 1),
}


class ProfileCard(MDCard):
    def __init__(self, profile, on_edit, on_delete, on_select, **kw):
        super().__init__(**kw)
        self.p = profile
        self.orientation = 'vertical'
        self.padding = dp(14)
        self.spacing = dp(6)
        self.radius = [12]
        self.elevation = 2
        self.size_hint_y = None
        self.adaptive_height = True
        self.md_bg_color = (0.12, 0.12, 0.20, 1)

        typ   = profile.get('type', 'xray')
        proto = profile.get('proto', typ).upper()
        name  = profile.get('name', 'Profil')
        host  = profile.get('host') or profile.get('address', '')
        port  = profile.get('port', '')
        color = TYPE_COLORS.get(typ, (0.5, 0.5, 0.5, 1))

        # Top row: badge + name
        top = MDBoxLayout(size_hint_y=None, height=dp(30), spacing=dp(8))
        badge = MDCard(size_hint=(None, None), size=(dp(68), dp(22)),
                       radius=[6], md_bg_color=color, elevation=0)
        badge.add_widget(MDLabel(text=proto, font_style='Caption', bold=True,
                                 halign='center', theme_text_color='Custom',
                                 text_color=(1, 1, 1, 1)))
        top.add_widget(badge)
        top.add_widget(MDLabel(text=name, font_style='Subtitle1', bold=True))
        self.add_widget(top)

        # Host info
        self.add_widget(MDLabel(
            text=f'{host}:{port}', font_style='Caption',
            theme_text_color='Secondary',
            size_hint_y=None, height=dp(20)))

        # Action buttons
        btns = MDBoxLayout(size_hint_y=None, height=dp(36), spacing=dp(6))
        for label, cb in [
            ('Pilih',  lambda *a: on_select(profile)),
            ('Edit',   lambda *a: on_edit(profile)),
            ('Hapus',  lambda *a: on_delete(profile)),
        ]:
            b = MDFlatButton(text=label, font_size='11sp')
            b.bind(on_release=cb)
            btns.add_widget(b)
        self.add_widget(btns)


class ProfilesScreen(MDScreen):
    def __init__(self, **kw):
        super().__init__(**kw)
        self.name = 'profiles'
        self._build()

    def _build(self):
        root = MDBoxLayout(orientation='vertical', spacing=dp(10),
                           padding=[dp(14), dp(8)])

        # Header
        hdr = MDBoxLayout(size_hint_y=None, height=dp(48), spacing=dp(8))
        hdr.add_widget(MDLabel(text='Profil Server', font_style='H6', bold=True))
        add_btn = MDRaisedButton(text='+ Tambah', size_hint_x=None, width=dp(100))
        add_btn.bind(on_release=lambda x: self._open_form(None))
        hdr.add_widget(add_btn)
        paste_btn = MDFlatButton(text='Paste Link')
        paste_btn.bind(on_release=lambda x: self._paste_link_dialog())
        hdr.add_widget(paste_btn)
        root.add_widget(hdr)

        sv = MDScrollView()
        self.list_box = MDBoxLayout(
            orientation='vertical', spacing=dp(10),
            size_hint_y=None, adaptive_height=True)
        sv.add_widget(self.list_box)
        root.add_widget(sv)
        self.add_widget(root)

    def on_enter(self):
        self._render()

    def _render(self):
        self.list_box.clear_widgets()
        profiles = list_profiles()
        if not profiles:
            self.list_box.add_widget(MDLabel(
                text='Belum ada profil.\nTekan + Tambah untuk membuat.',
                halign='center', theme_text_color='Secondary',
                size_hint_y=None, height=dp(80)))
            return
        for p in profiles:
            card = ProfileCard(
                p,
                on_edit=self._open_form,
                on_delete=self._confirm_delete,
                on_select=self._select_profile)
            self.list_box.add_widget(card)

    def _select_profile(self, p):
        Snackbar(text=f"Profil '{p.get('name')}' dipilih!",
                 snackbar_x=dp(10), snackbar_y=dp(10)).open()
        from kivy.storage.jsonstore import JsonStore
        JsonStore('vpn_state.json').put('state', active_profile=p.get('id',''))
        if self.manager:
            self.manager.get_screen('home').active_profile = p
            self.manager.get_screen('home')._load_profile()
            self.manager.current = 'home'

    def _open_form(self, profile=None):
        is_edit = profile is not None
        typ = profile.get('type', 'sshws') if profile else 'sshws'

        content = MDBoxLayout(
            orientation='vertical', spacing=dp(8),
            size_hint_y=None, height=dp(480))

        self._f = {}
        fields_ssh = [
            ('name',     'Nama Profil',        profile.get('name', '') if profile else ''),
            ('host',     'Host / IP Server',   profile.get('host', '') if profile else ''),
            ('port',     'Port WebSocket',      str(profile.get('port', 80)) if profile else '80'),
            ('username', 'Username SSH',        profile.get('username', '') if profile else ''),
            ('password', 'Password SSH',        profile.get('password', '') if profile else ''),
            ('ws_path',  'WS Path (/)',         profile.get('ws_path', '/') if profile else '/'),
        ]
        fields_xray = [
            ('name',    'Nama Profil',   profile.get('name', '') if profile else ''),
            ('address', 'Host / IP',     profile.get('address', '') if profile else ''),
            ('port',    'Port',          str(profile.get('port', 443)) if profile else '443'),
            ('uuid',    'UUID / Pass',   profile.get('uuid', '') if profile else ''),
            ('proto',   'Proto (vmess/vless/trojan/ss)',
                                         profile.get('proto', 'vmess') if profile else 'vmess'),
            ('network', 'Network (ws/tcp/grpc)',
                                         profile.get('network', 'ws') if profile else 'ws'),
            ('path',    'Path (/)',       profile.get('path', '/') if profile else '/'),
            ('sni',     'SNI (domain)',   profile.get('sni', '') if profile else ''),
        ]

        # Type selector
        type_row = MDBoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        self._type_val = typ
        for t, label in [('sshws', 'SSH-WS'), ('xray', 'Xray')]:
            btn = MDRaisedButton(text=label, font_size='12sp')
            if t == typ:
                btn.md_bg_color = (0.47, 0.44, 0.97, 1)
            btn.bind(on_release=lambda x, v=t: setattr(self, '_type_val', v))
            type_row.add_widget(btn)
        content.add_widget(type_row)

        fields = fields_ssh if typ == 'sshws' else fields_xray
        for key, hint, val in fields:
            tf = MDTextField(hint_text=hint, text=val, mode='rectangle',
                             size_hint_y=None, height=dp(48))
            self._f[key] = tf
            content.add_widget(tf)

        self._edit_profile = profile
        dlg = MDDialog(
            title='Edit Profil' if is_edit else 'Profil Baru',
            type='custom', content_cls=content,
            buttons=[
                MDFlatButton(text='BATAL',
                    on_release=lambda x: dlg.dismiss()),
                MDRaisedButton(text='SIMPAN',
                    on_release=lambda x: [dlg.dismiss(), self._save_form()]),
            ])
        dlg.open()

    def _save_form(self):
        f = self._f
        typ = getattr(self, '_type_val', 'sshws')
        p = dict(self._edit_profile) if self._edit_profile else {}
        p['type'] = typ
        for key, tf in f.items():
            val = tf.text.strip()
            if key == 'port' and val.isdigit():
                p[key] = int(val)
            else:
                p[key] = val
        save_profile(p)
        self._render()
        Snackbar(text='Profil disimpan!',
                 snackbar_x=dp(10), snackbar_y=dp(10)).open()

    def _confirm_delete(self, p):
        dlg = MDDialog(
            title='Hapus Profil',
            text=f"Hapus '{p.get('name')}'?",
            buttons=[
                MDFlatButton(text='BATAL', on_release=lambda x: dlg.dismiss()),
                MDRaisedButton(
                    text='HAPUS', md_bg_color=(0.85, 0.2, 0.2, 1),
                    on_release=lambda x: [
                        dlg.dismiss(),
                        delete_profile(p.get('id','')),
                        self._render()
                    ]),
            ])
        dlg.open()

    def _paste_link_dialog(self):
        content = MDBoxLayout(size_hint_y=None, height=dp(60))
        self._link_field = MDTextField(
            hint_text='vmess:// atau vless:// atau trojan://',
            mode='rectangle')
        content.add_widget(self._link_field)
        dlg = MDDialog(
            title='Import dari Link',
            type='custom', content_cls=content,
            buttons=[
                MDFlatButton(text='BATAL', on_release=lambda x: dlg.dismiss()),
                MDRaisedButton(text='IMPORT',
                    on_release=lambda x: [dlg.dismiss(), self._import_link()]),
            ])
        dlg.open()

    def _import_link(self):
        link = self._link_field.text.strip()
        try:
            p = XrayEngine.parse_link(link)
            p['type'] = 'xray'
            save_profile(p)
            self._render()
            Snackbar(text='Link berhasil diimpor!',
                     snackbar_x=dp(10), snackbar_y=dp(10)).open()
        except Exception as e:
            Snackbar(text=f'Gagal: {e}',
                     snackbar_x=dp(10), snackbar_y=dp(10)).open()
