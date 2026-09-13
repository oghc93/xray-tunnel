[app]
title = VPN Client
package.name = vpnclient
package.domain = org.vpnclient
source.dir = .
source.include_exts = py,png,jpg,kv,atlas,json,so
version = 1.0

# Dependensi Python
requirements = python3,kivy==2.3.0,kivymd==1.2.0,requests,paramiko,websocket-client,plyer

# Orientasi & layar
orientation = portrait
fullscreen = 0

# Izin Android
android.permissions = INTERNET,ACCESS_NETWORK_STATE,FOREGROUND_SERVICE

# Android SDK - gunakan versi yang stabil
android.api = 33
android.minapi = 26
android.ndk = 25b
android.accept_sdk_license = True
android.arch = arm64-v8a

# Sertakan folder assets (xray binary didownload saat build)
android.add_src = assets

# Presplash
# presplash.filename = %(source.dir)s/data/presplash.png
# icon.filename = %(source.dir)s/data/icon.png

[buildozer]
log_level = 2
warn_on_root = 1
