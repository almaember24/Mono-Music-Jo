# Community Looks

Looks people made and shared, listed in Prism under **Settings → Appearance → Looks → From the community**. Prism
downloads this folder's `index.json` only when someone opens that list, and a Look's file only when someone uses it.
Every Look shows as a preview first, and a Look can only change how Prism looks: never accounts, playback or anything
else.

## Add yours

1. Make your Look in Prism, then save it: **Appearance → Looks → Save as a Look**.
2. On its card, tap **⋯ → Share → Send as text**, and copy the code (it starts with `PRISMLOOK1:`).
3. Add an entry to the end of `looks` in [`index.json`](index.json):

   ```json
   {
     "id": "your-look-name",
     "name": "Your Look's Name",
     "by": "your-github-name",
     "note": "What it is, in a few words",
     "code": "PRISMLOOK1:…"
   }
   ```

   - `id`: lowercase letters, digits and hyphens, up to 40 characters, not used by another Look.
   - `name`: up to 40 characters. `note`: up to 80.
4. Open a pull request. Each Look is looked at before it's added.

### A Look with its own photos

Codes leave out background photos. To include them, use **Share → Send as a file** with **Include its photos** on,
name the file `<id>.prismlook`, add it to this folder, and add `"file": "<id>.prismlook"` to your entry (keep the
`code` too: Prism draws the Look's card from it). Files can be up to 8 MB. Only include photos you took or may share.
Fonts can't be added here.

### Checks

`ThemeGalleryTest` (in `app/src/test`) reads this folder: every entry needs a unique, valid id, a code that reads as a
Look holding only look settings, and its file when it names one. Run `./gradlew :app:testDebugUnitTest --tests
com.prism.music.ThemeGalleryTest` before opening the pull request.
