# Translating Cannoli

Cannoli's interface can be translated by anyone, no coding required. All
translation happens in a web editor on Crowdin.

## How to help

1. Create a free account at [Crowdin](https://crowdin.com).
2. Open the Cannoli project: https://crowdin.com/project/cannoli
3. Pick your language and start translating. If your language is not listed,
   open an issue and we will add it.

Approved translations are pulled into the app automatically, so there is
nothing to install or upload.

## Credit

Translators are listed in the app under Settings, About, Credits, grouped by
language. That list is refreshed from Crowdin every week, so there is nothing
to add yourself to. You will appear once you have translated at least one word.

The name shown is your Crowdin full name if you have set one, otherwise your
username. If you would rather be credited differently, change your Crowdin
profile name and it will follow on the next refresh.

## A few rules that keep translations working

- **Keep placeholders exactly as written.** Text like `%1$s`, `%d`, or `%%`
  is replaced with live values (a name, a number, a percent sign) when the app
  runs. Every placeholder in the source must appear in your translation.
- **You may reorder placeholders** to fit your language's grammar. For
  example `%1$s connected to %2$s` can become `%2$s ... %1$s` if that reads
  more naturally. Just do not drop or rename them.
- **UPPERCASE labels are a style choice.** Words like `BACK` and `PLAY` are
  shown in caps by design. Use whatever casing is natural for your language;
  the app does not require caps.
- **Plurals matter.** Some entries have separate forms for one vs. many.
  Crowdin shows the forms your language needs; fill in each one.

Crowdin automatically flags a translation that drops or breaks a placeholder,
so if you see a warning, check that every `%...` from the source is present.

## Questions

If a string is unclear, leave a comment on it in Crowdin and a maintainer will
add context.
