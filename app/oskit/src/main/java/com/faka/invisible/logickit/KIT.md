# Logic Kit

The shared logic of the OS: text, language, message wording, notifications, the tree, tasks, learning. **Before you write any helper, look here.** If it exists, call it. If it is almost right, extend it. Only when nothing fits, add it here (new shared helpers go in this folder; the table points to older logic that lives elsewhere).

Rules are checked by `KitRulesTest` and `checkVoiceKit` (file `app/voicekit-rules.tsv`, rule ids starting `logic-`). They fail with file:line. Pure logic has a unit test next to it; a rule that only matches one pattern is a floor, not a promise that nothing else is duplicated, so search this table by hand too.

## In this folder (`logickit/`)
| File | What it is |
|---|---|
| Text.kt | `String.squash()` = collapse white space and trim. `String.words()` = the words of a text. The only place that writes `Regex("\\s+")`. |
| UniqueNames.kt (in :os, root package: it works on AppLeaf) | `UniqueNames.apply(leaves, reserved)` = every spoken leaf name unique (no screen: two same names cannot be told apart). A program keeps its name; an app gets its maker ("Xiaomi Calculator"), then a number. Used by `TreeLeaves.edit`. |
| ReactionGap.kt | `ReactionGap(samples).gap(userSetting)` = the pause between scanned items, learned from the reaction times of the user (ms from the end of the picked item to the press): p90 + 300, between 700 ms and the setting; under 8 samples = the setting. `add`, `encode`/`decode` for saving. Used by `ControllerMode` (its pause). |
| SuggestGate.kt (in :os, root package: it works on AppLeaf) | `SuggestGate.worth(share, treeCost, position)` = is a suggested app worth a front place at home (share * saved > (1 - share) * 1, saved > 1). `treeCost(heard, levels)`. Used by the hub build in `ControllerMode` through `Suggester.top(accept = ...)`. |
| MusicSearch.kt | `MusicSearch.body(text)` / `.first(json)` = one song for a typed text from YouTube Music web search (songs only): id, title, artist. Pure; `Actions` does the network call off the main thread. Used by the Music page search. |
| TreeSearch.kt (in :os, root package: it works on AppLeaf) | `TreeSearch.search(root, findPlan, query)` = where an app or option sits: the clicks down the tree ("Media (2nd) > Volume (2nd)") and the Find steps. Used by the search box on the Menu page of `MainActivity`. |
| NameFinder.kt (in :os, root package: it works on AppLeaf) | `NameFinder.plan(leaves)` = the lists of Find (a hold on home): leaves sorted by name, split into at most 6 neighbouring parts per list (one leaf = its name, more = a range of whole first letters such as "A to C"; cuts never fall inside a letter; a letter with up to 12 apps is read whole, most used first; more is split by the first two letters into ranges such as "Wa to We", never "Other"), A to Z, split chosen for the lowest expected items heard. Slow on a phone: built off the main thread (`ModeOutput.background`). Used by `ControllerMode` (Screen.FIND). |
| NameLang.kt | `NameLang.of(pkg, label)` = "es" or "en" for an app name (package table, then Spanish words/letters, else en); `voice()` = "es" or null. Used by `ControllerMode.treeLabel` (tree, Find, Suggested). |
| Calculator.kt | `Calculator` = state of the spoken calculator (digit, point, operate, equals, undo, `spoken()`), left to right; `%` = percent of. Used by the Calculator program. |
| PriceSum.kt | `PriceSum` = running total of prices in cents (`add("12.5")`, `undo()`, `spoken()`). Used by the Sum prices tool (`launchSum` in Actions). |
| ChatLang.kt | `ChatLang.of(seen, detect)` = the language of the chat or message a reply goes to (null if too short or unknown). `needsTranslation(target)`. Used by Write for screen chats and for notification replies. |

## Elsewhere (use these, do not copy them)
| Need | Use | Where |
|---|---|---|
| Detect a text's language | `MlKitLangDetector` (`LangDetector`), falls back to `MessageLogic.guessLang` | MlKitLangDetector.kt, MessageLogic.kt |
| Translate English to the chat language | `MlKitTranslator` (a missing model is an answer, not a silent skip) | MlKitTranslator.kt |
| Strip links, spot "Photo" placeholders, make a link speakable, a person's stable name | `MessageLogic.stripLinks / isPlaceholder / spoken / stableName / wordingLang / voiceLang` | MessageLogic.kt |
| Classify a message and word the answers | `MessageLogic` (MsgType, Answer, When, Extra) | MessageLogic.kt |
| Latest unread message per conversation | `MessageStore` | MessageStore.kt |
| All notifications, groups, one by key (`notifs.get(key)`) | `NotificationStore` (`Notif`, `NotifAction`); chat messages are a different store (`ControllerNotificationListener.store`) | `Notif` in `oskit/Notifications.kt`, the store in `os/.../NotificationStore.kt` |
| How one app's notifications are read, grouped, replied to | `NotificationModule` + the registry | `oskit/Notifications.kt` (the interface, `Notif`, `NotificationHost`), one file per outside app in `app/adapters/` (ChatAppsModule.kt, TeamsModule.kt), one line each in `Adapters` in `os/.../Programs.kt` |
| Answer a notification (fire an action, reply, dismiss, mute) | `ControllerNotificationListener.fireAction / sendReply` | ControllerNotificationListener.kt |
| Where an app sits in the spoken tree | `AppCategories`, `AppWeights`, `AppTree`, `TreeLeaves` (edits in `LauncherSettings`) | those files |
| Which apps exist and how often they were opened | `DeviceApps` | DeviceApps.kt |
| Tasks, and offering past texts again | `TaskStore`, `PhraseMemory` | TaskStore.kt, PhraseMemory.kt |
| What to offer first in the hub | `Suggester` | Suggester.kt |
| The place Write types into (app, field kind, screen text) | `Frame`, `BucketStore`, `BucketRanker` | Context.kt |
| Next word (English) | `NextWord` | NextWord.kt |
| Phone AI (replies, endings, tasks) | `LocalAi`, `ModelDownload` | LocalAi.kt, ModelDownload.kt |
| Weather and navigation sentences | `Weather`, the nav parser | Info.kt |
| Local choice log and gesture log | `ChoiceLog`, `EventLog` | ChoiceLog.kt, EventLog.kt |
| Speak, play, read the button, a list | the **Voice Kit** | voicekit/KIT.md |
| Colors, rows, switches | the **UI Kit** | Ui.kt |

## Add something
1. Search this table and `krn find` for it. Reuse if it exists.
2. If you copy the same lines a second time, move them here, make the call sites use it, and add a row.
3. Pure logic only (no Android, no I/O, no speech): it must have a unit test. Anything that touches Android goes to an engine/door that already exists, or is a new row in the table.
4. If a duplicate has a clear text pattern, add a `logic-` rule to `voicekit-rules.tsv` and its test line in `KitRulesTest`.
