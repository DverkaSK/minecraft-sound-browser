# Changelog

## [1.0.0]

- Sound browser tool window with all sound events grouped by category and group
- Instant search that accepts keys, Bukkit constants, namespaced keys and plain words
- Player with interactive waveform, seeking, pause, loop, variants, live volume and pitch
- Inline ▶ buttons after `Sound.X`, `SoundEvents.X` and `"minecraft:..."` keys in Java and Kotlin, with a mini player popup that can switch between sound variants (optional gutter icons); the button color follows the IDE accent or can be picked in the settings
- Also detects Sponge `SoundTypes.X`, statically imported constants, pre-1.9 Bukkit names (`Sound.LEVEL_UP`), and works in Groovy, Scala, JSON and plain text (`.mcfunction`)
- Plays your mod's or resource pack's own sounds from `assets/<namespace>/sounds.json`: `ModSounds.X` constants, `"mymod:..."` keys and `sounds.json` entries
- Copy sounds as key, Bukkit constant, `playSound` call, Adventure sound or `/playsound` command
- Favorites, any release or snapshot since 1.7.2, offline cache with SHA-1 verification
