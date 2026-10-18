@echo off
rem W-layer browser launcher (double-click THIS to start it in your own desktop session).
rem --disable-gpu avoids the black/unresponsive window seen with a fresh profile.
rem Then log into chatglm.cn / chat.deepseek.com. w-brain-drive.js attaches via CDP 9223.
set "EDGE=C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
set "PROFILE=%USERPROFILE%\.playwright-profile-w"
start "" "%EDGE%" --remote-debugging-port=9223 --user-data-dir="%PROFILE%" --no-first-run --no-default-browser-check --disable-gpu --disable-gpu-compositing --new-window https://chatglm.cn/ https://chat.deepseek.com/
