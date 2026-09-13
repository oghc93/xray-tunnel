"""
VPN Client App - seperti HTTP Custom
Kontrol SSH-WS dan Xray dari Android
"""
from kivymd.app import MDApp
from kivymd.uix.screenmanager import MDScreenManager
from kivymd.uix.navigationbar import (
    MDNavigationBar, MDNavigationItem,
    MDNavigationItemIcon, MDNavigationItemLabel,
)
from kivymd.uix.boxlayout import MDBoxLayout
from kivy.metrics import dp

from screens.home     import HomeScreen
from screens.profiles import ProfilesScreen
from screens.payload  import PayloadScreen
from profile_store    import list_profiles, save_profile, DEFAULT_PROFILES


NAV = [
    ('home',     'access-point',   'Konek'),
    ('profiles', 'server-network', 'Profil'),
    ('payload',  'code-tags',      'Payload'),
]


class VpnClientApp(MDApp):
    def build(self):
        self.theme_cls.theme_style    = 'Dark'
        self.theme_cls.primary_palette = 'DeepPurple'
        self.title = 'VPN Client'

        # Seed demo profiles if empty
        if not list_profiles():
            for p in DEFAULT_PROFILES:
                save_profile(dict(p))

        root = MDBoxLayout(orientation='vertical')

        self.sm = MDScreenManager()
        self.sm.add_widget(HomeScreen())
        self.sm.add_widget(ProfilesScreen())
        self.sm.add_widget(PayloadScreen())
        root.add_widget(self.sm)

        nav = MDNavigationBar(on_switch_tabs=self._switch)
        for name, icon, label in NAV:
            nav.add_widget(MDNavigationItem(
                MDNavigationItemIcon(icon=icon),
                MDNavigationItemLabel(text=label),
                name=name,
            ))
        root.add_widget(nav)
        return root

    def _switch(self, bar, item, name):
        self.sm.current = name


if __name__ == '__main__':
    VpnClientApp().run()
