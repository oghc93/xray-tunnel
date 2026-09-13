[app]
title = VPN Client
package.name = vpnclient
package.domain = org.vpnclient
source.dir = .
source.include_exts = py,png,jpg,kv,atlas,json,so
version = 1.0

# Dependensi Python
requirements = python3,kivy==2.3.0,kivymd==1.2.0,requests,paramiko,websocket-client,android,plyer

# Orientasi & layar
orientation = portrait
fullscreen = 0

# Izin Android
android.permissions = INTERNET,ACCESS_NETWORK_STATE,FOREGROUND_SERVICE
android.foreground_service = 1
android.foreground_service_type = dataSync

# Android SDK
android.api = 33
android.minapi = 26
android.ndk = 25b
android.accept_sdk_license = True
android.arch = arm64-v8a

# File xray binary ikut disertakan
android.add_src = assets

[buildozer]
log_level = 2
warn_on_root = 1
