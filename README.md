# ¡Venga! — Madrid Spanish flash cards

An Android vocabulary builder. You see an English word, say it in Spanish, and the app checks your answer against Madrid (Spain) Spanish, slang included.

## Getting the app on your phone

Every push to `main` builds a new APK automatically (the **Actions** tab shows progress, about 5 minutes).

1. On your phone, open this repository's **Releases** page (signed in to GitHub).
2. Tap the newest release, then the `Venga-1.0.N.apk` file to download it.
3. Open the download. If Android asks, allow your browser or Files app to "install unknown apps", then tap **Install**.

New versions install over the old one and keep your progress, because every build is signed with the same key (`app/venga.keystore`). **Keep this repository private.**

## How it works

- **Speak or type.** Tap the mic and say the Spanish. The phone's speech recogniser listens in Spain Spanish (es-ES). Accents, capitals and leading articles (el, la, un…) don't matter. Use "Type it" if you're somewhere you can't talk.
- **Madrid first.** If you give a Latin American word (carro, computadora, jugo…), the app tells you what Madrid says instead.
- **Pronunciation.** 🔊 plays the word in your phone's Spain-Spanish voice; 🐢 plays it slowly. Choose between installed Spain voices in Ajustes.
- **Spaced repetition.** Words you get right come back after 1, 3, 7, 14, 30 and then 60 days. Words you miss come back later in the same session and again next time.
- **Decks.** Intermedio and Pasado are open from the start, and new words alternate between them. Avanzado unlocks at 60% of Intermedio, Callejero at 60% of Avanzado, or you can unlock either early.
- **Pasado.** About 50 high-use verbs in the preterite (fui), the perfect (he ido, which Madrid uses for anything today) and the imperfect (iba), including vosotros. Say the right verb in the wrong tense and the app tells you so. Starting with yo, tú and so on is fine.
- **Crude words** are tagged CRUDE and can be switched off in Ajustes.

## Phone setup tips

- **Voice:** if there's no Spain-Spanish voice, install one: Settings › Text-to-speech › Speech Services by Google › Install voice data › Spanish (Spain).
- **Offline speech:** to answer without internet, download Spanish (Spain) offline speech in the Google app's voice settings.
- **Swear words showing as \*\*\*\*:** Google's recogniser masks them. The app still accepts masked answers, but you can turn off "Block offensive words" in the Google app's voice settings.

## Adding words

Edit `app/src/main/assets/words.txt`, one word per line:

```
level|English prompt|answer;other accepted answer|tag|note|non-Madrid words|other tenses
1|the car|el coche||In Spain it's always coche.|el carro;el auto
```

Tag `s` = slang, `v` = crude, blank = standard. Push the change and a new APK builds.
