# Third-party notices

## TerminalMC/CommandKeys

The `dev.nexoclient.nexomod.macro` package (keybind-triggered chat/command
macros) took its feature set — multiple commands per macro, several send
modes (send all/cycle/random/repeat/type-without-sending), and a few
message placeholders — from
[CommandKeys](https://github.com/TerminalMC/CommandKeys), used under the
Apache License 2.0. `NexoMacro`/`NexoMacroConfig`/`NexoMacroDispatcher`
(data model, persistence, tick-based key polling) are original, since
CommandKeys' equivalents are entangled with features not adopted here.

Its screen framework **is** a direct port, since CommandKeys happens to
target the same Minecraft version (26.1.2) this mod does:
`NexoTextField`, `NexoOptionList` (+ its `Entry`/`Entry.Text`/
`Entry.ActionButton`/`Entry.Space` inner classes), and `NexoOptionScreen`
are adapted from CommandKeys' `gui/widget/field/TextField.java`,
`gui/widget/list/OptionList.java`, and `gui/screen/OptionScreen.java`
respectively — same class shapes and method bodies, renamed and with a few
CommandKeys-specific pieces removed (its custom-sprite `WidgetSprites`
icon buttons, since this mod doesn't have or want those textures; plain
vanilla `Button`s are used instead). `NexoMacroOptionList` and
`NexoMacroEditOptionList` (the actual macro-list and macro-edit row
layouts) are modeled closely on `MainOptionList`'s profile-row pattern and
`MacroOptionList`'s field-row pattern, adapted to this mod's simpler
single-list `NexoMacro` model. The point of building on this framework
rather than a hand-designed screen: it's built entirely from plain
vanilla widgets (`Screen`/`AbstractButton`/`CycleButton`/`EditBox`), so
the mod's own global re-skin mixins (`NeonButtonMixin`,
`NeonMenuBackgroundMixin`) reskin it automatically like any other screen
in the game, rather than needing bespoke Nexo-styled chrome.

Not ported: profiles and per-server auto-switching, ratelimiting, conflict
strategies, dual keybinds, drag-to-reorder, per-message delay, "MC
activator key" command-block-style triggers, and the HUD/history/
resume-repeat toggles — those are CommandKeys features this mod's simpler
macro model doesn't have.

```
Copyright 2026 TerminalMC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## Fix85/SelfNametag

`src/main/java/dev/nexoclient/nexomod/mixin/OwnNameTagVisibilityMixin.java`
adapts the local-player nametag-visibility technique from
[Fix85/SelfNametag](https://github.com/Fix85/SelfNametag) (`mc-26.1` branch),
used under the MIT License:

```
MIT License

Copyright (c) 2026 Fix85

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## vgskye/e4mc-minecraft-architectury (e4mc)

The `dev.nexoclient.nexomod.lantunnel` package and
`src/main/java/dev/nexoclient/nexomod/mixin/LanTunnelHostMixin.java` adapt
the LAN-over-internet relay tunnel from
[e4mc](https://github.com/vgskye/e4mc-minecraft-architectury) (`rererewrite`
branch), used under the MIT License. By default this tunnel connects to
e4mc's own public relay/broker infrastructure (`e4mc.link`) — see
`LanTunnelConfig.java` to point it at a different relay instead.

Ported: the relay-tunnel host path only (`QuiclimeSession` ->
`LanTunnelSession`, `ServerConnectionListenerMixin` ->
`LanTunnelHostMixin`, `E4mcClient` -> `LanTunnel`). Not ported: e4mc's
optional direct peer-to-peer path ("Dialtone", built on the Iroh library)
and its LAN-world ban/whitelist-restoration commands.

```
MIT License

Copyright (c) 2024 Skye

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## axieum/authme

`src/main/java/dev/nexoclient/nexomod/auth/MicrosoftAuth.java` adapts the
Microsoft OAuth2 authorization-code sign-in flow (browser login + localhost
redirect callback) from [authme](https://github.com/axieum/authme)
(`common/src/main/java/me/axieum/mcmod/authme/api/util/MicrosoftUtils.java`),
used under the MIT License. Also reuses authme's own registered Azure AD
public client id (`e16699bb-2aa8-46da-b5e3-45cbcce29091`) rather than
requiring users to register their own Azure app — this id is a public,
non-secret OAuth2 client identifier, not sensitive data.

Ported: the browser-based authorization-code exchange, Xbox Live/XSTS/
Minecraft-services token chain, and the `Minecraft.user`
accessor-mixin-with-`@Mutable` technique used to swap the active session
without a restart. Not ported: authme's full session-service rebuild
(profile-key-pair manager, Realms client, reporting context, friends/social
services) or its multi-account persistence — Nexo Mod has its own encrypted
multi-account store (`AccountStore`) instead of authme's single-session model.

```
MIT License

Copyright (c) 2020-2026 Axieum

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Noto Sans

`src/main/resources/assets/nexomod/font/noto_sans.ttf` and
`src/main/resources/assets/minecraft/font/default.json` (which overrides
vanilla's own `default.json` to use it for Latin/Greek/Cyrillic text
game-wide, while keeping vanilla's own bitmap fonts as a fallback for
everything else — box-drawing characters, CJK via unifont, etc.) bundle
[Noto Sans](https://fonts.google.com/noto) (the `latin-greek-cyrillic`
subset, Copyright 2022 The Noto Project Authors), used under the SIL Open
Font License, Version 1.1. Sourced from Arch Linux's `noto-fonts` package.

```
Copyright 2022 The Noto Project Authors (https://github.com/notofonts/latin-greek-cyrillic)

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
https://openfontlicense.org

-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded,
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```

## RedLime/DetailArmorBar

`assets/nexomod/textures/gui/armor_bar.png` — the armour bar's icon atlas —
is taken **unchanged** from
[Detail Armor Bar](https://github.com/RedLime/DetailArmorBar), used under the
MIT License. It is the only vendored *asset* in this repo, which is why this
file now ships inside both jars rather than only living here: a jar on a
release page is a copy, and MIT asks the notice to travel with every copy.

The code in `dev.nexoclient.nexomod.hud.NexoArmorBar` /
`NexoArmorBarLayout` / `NexoArmorBarEffects` is **not** a port. Detail Armor
Bar targets 1.21.4 and draws through `Tessellator`, `RenderSystem.setShader`
and `BufferRenderer`, none of which exist in 26.1; and where it patches
`InGameHud.renderArmor` with a mixin, this replaces Fabric's
`VanillaHudElements.ARMOR_BAR` and needs none. What was taken from it, beyond
the artwork, is the *idea* that armour is counted in points rather than icons,
so one icon can be half one piece and half another — plus the protection
colours and the pulse timings, which are conventions worth matching rather
than reinventing.

Fresh Armor Bar, which prompted the feature, is LGPL-3.0 and was **not**
read. It contributed the notion of reacting to the damage type; the
implementation here is written against `LivingEntity.getLastDamageSource`
and `DamageTypeTags` from scratch.

```
MIT License

Copyright (c) 2021 RedLime

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
