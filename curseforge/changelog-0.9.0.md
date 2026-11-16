## 0.9.0 — Lock a single point, type the coordinates

**Added**

- **Lock one point, from the world.** With a chain in your main hand and the crosshair on a point's pin, a right-click flips that one point's lock (the edit box does not open). A locked point refuses to be dragged; every other point still moves as usual. The lock is stored with the point, travels through the server sync, and shows as a dimmed pin in the world plus a small marker and a "· locked" suffix in the map tooltip. `L` still toggles every point at once. Turning "edit points with an item" off leaves the chain working — otherwise a locked point could never be unlocked again.
- **X and Z can be typed.** The world box grew from three fields to six (X, Z, Y, name, kind, lock) and the map's double-click box from three rows to five (X, Z, Y, name, kind). X / Z are clamped to the world border (±30,000,000), Y to −1024…1024. Typing X / Z puts the point on that column and keeps the Y you typed instead of re-sampling the ground; clear the Y and press Enter to sample it again.
- **The chain item is a setting.** Left column of the settings page: `poiLockItem`, default `minecraft:iron_chain`. Minecraft renamed the chain in 1.21.9 — `minecraft:chain` no longer exists — so the default is the new id.

**Fixed**

- A point whose Y was re-sampled (terrain data arrived, or it was resolved for the first time) used to lose its name and kind along the way; the lock would have gone the same way. The re-sample now carries the name, the kind and the lock over.

**Unchanged**

- The save format only gained one bit in a flags word that already had spare bits, and the sync payload is the same payload. Geometry, keybinds, the updater and the server side are untouched.
- A 0.8.x client still reads a 0.9.0 route — it simply ignores the lock bit — but that client writes the route back without it, so the lock survives only while every client that edits the line is 0.9.0 or newer.

---

## 0.9.0 —— 只锁一个点，坐标能手输

**新增**

- **在世界里只锁一个点。** 主手拿锁链，准星对准点位那根竖针右键，就把**这一个点**的锁翻过来（不弹框）。锁上之后拖不动它，别的点照常拖。锁存在点上，跟着服务端同步走：世界里那根针压暗一档、地图上这个点多一个小记号、悬停提示带「· 已锁」。`L` 仍然是「全锁」。把「拿物品右键改点」关掉之后锁链那半照样能用——否则锁住的点就再也解不开了。
- **X / Z 可以手输。** 世界里的框从三项变成六项（X、Z、Y、名字、类型、锁），地图里双击顶点那个框从三行变成五行（X、Z、Y、名字、类型）。X / Z 夹在世界边界 ±30000000 以内，Y 夹在 −1024…1024。手输 X / Z 是换个列、Y 保持你填的那个数，不会被新那一列的地面高度顶掉；想重新取地面高度，把 Y 清空再回车。
- **锁链用哪个物品是设置项。** 设置页左栏 `poiLockItem`，默认 `minecraft:iron_chain`。1.21.9 起锁链改了 id，`minecraft:chain` 已经不存在，所以默认值按新的写。

**修复**

- 一个点的 Y 被重新采样时（地形数据到了、或者第一次解析出来），名字和类型会跟着丢，锁本来也会一起丢。现在名字、类型、锁都原样带过去。

**不变**

- 存档格式只在一个本来就有空位的 flags 字里多用了一位，同步载荷还是同一个载荷。几何、键位、更新器、服务端全都没动。
- 0.8.x 的客户端照样读得懂 0.9.0 的线——它只是忽略锁位——但它把线写回去时会把锁位抹掉，所以这条线的锁只有在改它的客户端都是 0.9.0 及以上时才留得住。
