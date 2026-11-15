# Wayfarer's Atlas

A cozy parchment world map and minimap for **Minecraft 1.21.1 (NeoForge)**, kept inside an Atlas you carry.
*Уютная пергаментная карта и миникарта для Minecraft 1.21.1 (NeoForge), которая живёт внутри атласа. Русское описание ниже.*

## Features

- **Minimap and world map** drawn from real block colours: relief shading, water depth, biome tints (works with modded biomes and fluids)
- **Cave view** (automatic or manual height), **day and night** view where torches and lava glow
- **Markers** with 100 pixel-art icons and colours; share them in chat with a click-to-add button
- **The map lives in the Atlas.** Every Atlas has its own map. Lose it and the map is gone; hand it to a friend and they see everything you explored
- **Wayfarer's Map Table** (2 blocks wide): right-click it with your Atlas to leave what you explored and take what others left. Places you only saw on the table stay faded until you go there yourself. Markers can be shared too
- **Atlas pages** (upgrades put into the Atlas like items into a bundle):
  - *Creature Radar*: mobs around you on the maps, with their faces (hold TAB)
  - *Death Point*: a skull marker where you died (the last three) - teleport to it or share it like any marker
  - *Items*: items lying on the ground on the maps (hold TAB to see what they are)
- **Atlas entries**: one list of all markers, regions and rulers - search, filter by world, show on the map, edit, delete
- **Regions**: outline an area on the map and name it ("My base", "Village"); regions can be left on the map table
- **Markers in the world**: small faint marker icons in the world (name and distance when you look at one)
- **Map themes**: classic, old map, watercolour, topographic
- **Clean map** button: hide everything drawn on the map
- **Isometric view** (button on the big map): the land seen at an angle - hills, cliffs, trees and houses stand up; hold the middle mouse button to turn and tilt it
- **FTB Teams**: tick "For the team" on a marker or region and your whole team sees it at once; teammates are always shown on the maps
- **Lead me there**: pick a marker and follow the arrow at the top of the screen and the dashed red path on the maps
- **Import from Xaero's Minimap**: bring your Xaero's waypoints into the Atlas (marker list → Import)
- **Waystones support**: activating a waystone puts a marker with its name into your Atlas; renaming the waystone renames the marker (can be switched off in the settings)
- **Ruler** on the big map (button or R): click points to measure distances in blocks; rulers can be left on the map
- **Minimap**: square or round, north up or turning with you (settings); letters of the sides of the world
- **Burnt edges**: explored land ends in singed, torn paper (setting)
- **Trail**: a dotted line of where you walked during the last minute
- **Other worlds**: switch the big map between the Overworld, the Nether, the End and modded worlds you mapped; your position is converted (1 block in the Nether = 8 in the Overworld)
- **Server rules** (`serverconfig/wayfarers_atlas-server.toml`): switch off teleport, radar, death page, map table, trail, showing players, coordinates or the minimap; worlds where the map does not work; an Atlas for everyone who joins for the first time
- **Region names**: walking into a region shows its name (top of the screen, middle, or off) and the minimap shows the region you are in
- **For servers and modpacks**: see *Commands and resource packs* below
- **Teleport** from the map context menu (needs permission)
- **Atlas slot** next to the survival inventory: an Atlas kept there stays with you when you die

Open the map with **J**, add a marker with **N**. Settings: *Mods → Wayfarer's Atlas → Config* or the gear on the map.

## Crafting

| Item | Recipe |
|---|---|
| Atlas | Book and Quill + Map (shapeless) |
| Tracker Lens | Amethyst Shard surrounded by 4 Glass Panes |
| Creature Radar page | Lens on top, Feather · Map · String in the middle, Redstone below |
| Death Point page | Lens on top, Bone · Map · Bone below it |
| Items page | Lens on top, Gold Nugget · Map · Gold Nugget below it |
| Wayfarer's Map Table | Paper · Compass · Paper / 3 Planks / Log · – · Log |

---

## Описание (RU)

- **Миникарта и большая карта** из настоящих цветов блоков: рельеф, глубина воды, цвета биомов (работает с модовыми биомами и жидкостями)
- **Режим пещер** (автоматически или с ручной высотой), **день и ночь**: факелы и лава светятся в темноте
- **Метки** со 100 пиксельными иконками; ими можно делиться в чате — у друга появится кнопка «добавить метку»
- **Карта хранится в атласе.** У каждого атласа своя карта: потерял атлас — карта пропала, отдал другу — он видит всё, что ты исследовал
- **Стол путника** (2 блока): ПКМ атласом — твои исследования остаются на столе, а чужие переходят в атлас. Места, которые ты видел только на столе, бледные, пока сам туда не дойдёшь. Метками тоже можно обмениваться
- **Страницы атласа** (вкладываются в атлас, как предметы в мешок): *радар существ* показывает мобов вокруг, с мордочками (зажми TAB); *место смерти* — метка с черепом там, где ты умер (последние три); к ней можно телепортироваться и делиться ею, как обычной меткой; *предметы* — лежащие на земле предметы на карте (зажми TAB, чтобы увидеть какие)
- **Записи атласа**: один список всех меток, регионов и линеек — поиск, фильтр по мирам, показать на карте, изменить, удалить
- **Регионы**: обведи область на карте и назови её («Моя база», «Деревня»); регионы можно оставлять на столе путника
- **Метки в мире**: маленькие бледные значки меток в мире (имя и расстояние, если посмотреть на значок)
- **Темы карты**: классика, старинная, акварель, топографическая
- **Чистая карта**: кнопка, которая скрывает всё нарисованное на карте
- **Изометрия** (кнопка на большой карте): земля видна под углом — холмы, обрывы, деревья и дома стоят объёмно; зажми колёсико, чтобы вращать и наклонять
- **FTB Teams**: галочка «Для команды» у метки или региона — вся команда видит её сразу; сокомандники всегда видны на картах
- **Вести сюда**: выбери метку — сверху экрана появится стрелка, а на картах красный пунктир до неё
- **Импорт из Xaero's Minimap**: перенеси свои метки Xaero's в атлас (список меток → Импорт)
- **Поддержка Waystones**: активированный путевой камень сам появляется меткой с его названием в атласе; переименуешь камень — переименуется и метка (можно выключить в настройках)
- **Линейка** на большой карте (кнопка или R): ставь точки кликами и смотри расстояние в блоках; линейки можно оставлять на карте
- **Миникарта**: квадратная или круглая, север сверху или поворот вместе с тобой (настройки); буквы сторон света
- **Обожжённые края**: разведанная земля обрывается обожжённой рваной бумагой (настройка)
- **След**: пунктир там, где ты прошёл за последнюю минуту
- **Другие миры**: на большой карте можно переключаться между Верхним миром, Незером, Эндом и модовыми мирами; твоя позиция пересчитывается (1 блок в Незере = 8 в Верхнем мире)
- **Правила сервера** (`serverconfig/wayfarers_atlas-server.toml`): можно выключить телепорт, радар, страницу смерти, стол, след, показ игроков, координаты и миникарту; миры, где карта не работает; атлас каждому при первом входе
- **Названия регионов**: при входе в регион показывается его название (сверху экрана, по центру или никак), а под миникартой видно, в каком ты регионе
- **Для серверов и сборок**: смотри раздел *Commands and resource packs* ниже
- **Телепорт** из меню карты (нужны права)
- **Слот атласа** рядом с инвентарём: атлас в нём не выпадает при смерти

Карта — **J**, новая метка — **N**. Настройки: *Моды → Wayfarer's Atlas → Настройки* или шестерёнка на карте.

## Commands and resource packs

Commands are for operators. The world is the one the command is run in (`/execute in minecraft:the_nether run atlas ...`).

| Command | What it does |
| --- | --- |
| `/atlas region add <id> "<name>" <colour> <for> <x z> <x z> <x z> [more x z]` | A region from 3-30 corners (TAB gives the block you look at, like in /fill). The name goes in quotes (TAB puts them in). `for`: `all` - every Atlas shows it, also of new players, players cannot delete it; `all_removable` - every Atlas gets its own copy, players may change or delete it; `me` or a player name - only into the Atlas that player carries |
| `/atlas region remove <id>`, `rename <id> <name>`, `list` | Server regions |
| `/atlas region secret <id> <true/false>` | A secret region is shown to a player only after they walked into it |
| `/atlas marker add <id> "<name>" <icon> <colour> <for> <x y z>` | A marker (icon 0-99, or 100+ for icons of a resource pack); `for` as above |
| `/atlas marker remove <id>`, `rename <id> <name>`, `list` | Server markers |
| `/atlas marker secret <id> <true/false>` | A secret marker is shown to a player only after they came close to it |
| `/atlas lead <players> <x y z> [name]`, `/atlas lead <players> clear` | Starts / stops "Lead me there" to a place (quest goals) |
| `/atlas reveal start <radius>` | Draws the land round you into your Atlas, from the middle outwards, without flying over it |
| `/atlas reveal start <radius> generate` | The same, and land nobody has been near yet (not generated by the game) is generated first - slow, like pre-generating the world |
| `/atlas reveal start <radius> at <x z>`, `... for <players>` | Round another place / into other players' Atlases (they must be in that world); can be combined: `for @a at 0 0 generate` |
| `/atlas reveal stop` | Stops revealing |

Colours: `red orange yellow green cyan blue purple pink brown white gray black` or `RRGGBB`. Players cannot delete markers and regions made with `all`, only hide them in their own Atlas.

A resource pack can add marker icons - `assets/wayfarers_atlas/textures/gui/icons/custom/custom_00.png`, `custom_01.png`... (16x16, numbered without gaps) - and recolour the Atlas with `assets/wayfarers_atlas/atlas_style.json`:

```json
{
  "frame_dark": "2B1D10", "frame_wood": "5A3F22", "frame_gold": "B8934A", "frame_stud": "E8D29A",
  "parchment_top": "E3D1A6", "parchment_bottom": "CDB57F", "ink": "3A2410", "map_background": "2B2217"
}
```

## License

[LGPL-3.0](LICENSE) (with [GPL-3.0](COPYING)). You may use the mod in modpacks freely.
Можно свободно добавлять в сборки.
