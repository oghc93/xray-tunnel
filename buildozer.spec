[app]
title = VPN Client
package.name = vpnclient
package.domain = org.vpnclient
source.dir = .
source.include_exts = py,png,jpg,kv,atlas,json,so
version = 1.0

# Dependensi - urutan penting!
# openssl & cryptography harus sebelum paramiko
requirements = python3,kivy==2.3.0,kivymd==1.2.0,requests,openssl,cryptography,paramiko,websocket-client,plyer

orientation = portrait
fullscreen = 0

# Izin Android
android.permissions = INTERNET,ACCESS_NETWORK_STATE

# Android SDK
android.api = 33
android.minapi = 26
android.ndk = 25b
android.accept_sdk_license = True
android.arch = arm64-v8a

# Sertakan folder assets (xray binary)
android.add_src = assets

[buildozer]
log_level = 2
warn_on_root = 1
