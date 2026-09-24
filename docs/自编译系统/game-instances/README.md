# Game instances

`test` is the isolated self-compile and mutation experiment. `gpt` is the long-run Minecraft baseline. The launcher acquires an instance lock, assigns the registry port (`test=8765`, `gpt=8766` by default), and substitutes only the quick-play save name in the existing launcher. No credentials are copied into documentation.
