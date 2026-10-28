# Logic Kit

The shared logic of the OS: text, language, message wording, notifications, the tree, tasks, learning. **Before you write any helper, look here.** If it exists, call it. If it is almost right, extend it. Only when nothing fits, add it here (new shared helpers go in this folder; the table points to older logic that lives elsewhere).

Rules are checked by `KitRulesTest` and `checkVoiceKit` (file `app/voicekit-rules.tsv`, rule ids starting `logic-`). They fail with file:line. Pure logic has a unit test next to it; a rule that only matches one pattern is a floor, not a promise that nothing else is duplicated, so search this table by hand too.

## In this folder (`logickit/`)
| File | What it is |
|---|---|
| Text.kt | `String.squash()` = collapse white space and trim. `String.words()` = the words of a text. The only place that writes `Regex("\\s+")`. `String.firstWords(n)` = the first n words, "..." if cut (Claude bridge summaries). |
| UniqueNames.kt (in :os, root package: it works on AppLeaf) | `UniqueNames.apply(leaves, reserved)` = every spoken leaf name unique (no screen: two same names cannot be told apart). A program keeps its name; an app gets its maker ("Xiaomi Calculator"), then a number. Used by `TreeLeaves.edit`. |
| WhereAmI.kt | `WhereAmI.lines(Place, turn)` = the sentences of Where am I: next turn first (if navigating), then "street number", area, city (repeats said once). Empty = nothing known. Used by the `programs/whereami` tool. |
| ClickModel.kt | `ClickModel<K>(periodMs)` = the Nomon one-click model (GPL v3 port, see NOMON-LICENSE.md): items have a time in a repeating cycle (when they are spoken); `start(priors, timesMs)`, `click(atMs)` (true = an item is chosen), `posteriors()`, `best()`, `chosen()` (leads by more than ln 99), `learn(key)` (learns the click offsets into a damped 80-bin density, with a delay of 2 choices), `undo()`. No negative evidence (as Nomon). `offsets()`/`seed()`/`inherit(prev)` carry what was learned to a model of the next round (other cycle length). Used by the Voice Kit `LetterLoop` (Write). |
| ClickProfile.kt | `ClickProfile.of(offsetsMs)` = how late and how even one person clicks (mean ms, spread ms, count, 80-bin density in ClickModel's form); `toJson()` / `fromJson()`; saved under prefs key `ClickProfile.KEY` ("click_profile"); `params()` / `model(period)` = a ClickModel that starts from this person (needs 8+ clicks, else Nomon's start values). Made by the Click test tool (`programs/clicktest`). |
| ReactionGap.kt | `ReactionGap(samples).gap(userSetting)` = the pause between scanned items, learned from the reaction times of the user (ms from the end of the picked item to the press): p90 + 200, never under the setting (the slider = the shortest pause), at most 3000 ms; under 8 samples = the setting. `add`, `encode`/`decode` for saving. Used by `ControllerMode` (its pause). Kept after review of the 0.65 rule (step = mean press time / 0.65, Simpson 2006): it was made for visual scanning where the eye finds the item at once; audio has the word length plus the time to decide. Our p90 + 200 ms of the real gap after the speech already adapts to each user and is safer than a mean (a late press = a wrong pick). No audio-only source gives a better rule, so no change. |
| SuggestGate.kt (in :os, root package: it works on AppLeaf) | `SuggestGate.worth(share, treeCost, position)` = is a suggested app worth a front place at home (share * saved > (1 - share) * 1, saved > 1). `treeCost(heard, levels)`. Used by the hub build in `ControllerMode` through `Suggester.top(accept = ...)`. |
| HomeActions.kt | `HomeAction` (kind open/start/play/reply/call, name, target, prior), `ActionSource` (one class per source), `ActionMatch` (letters fit a name: prefix, later word, initials), `ActionRanker` (mix of sources, shares from picks), `SourceTrust`, `SongsPlayed` (songs the media sessions showed, newest and most played). Used by `HomeLetters` and `HomeSources` in :os. |
| MusicSearch.kt | `MusicSearch.body(text)` / `.first(json)` = one song for a typed text from YouTube Music web search (songs only): id, title, artist. Pure; `Actions` does the network call off the main thread. Used by the Music page search. |
| TreeSearch.kt (in :os, root package: it works on AppLeaf) | `TreeSearch.search(root, findPlan, query)` = where an app or option sits: the clicks down the tree ("Media (2nd) > Volume (2nd)") and the Find steps. Used by the search box on the Menu page of `MainActivity`. |
| NameFinder.kt (in :os, root package: it works on AppLeaf) | `NameFinder.plan(leaves)` = the lists of Find (a hold on home): leaves sorted by name, split into at most 6 neighbouring parts per list (one leaf = its name, more = a range of whole first letters such as "A to C"; cuts never fall inside a letter; a letter with up to 12 apps is read whole, most used first; more is split by the first two letters into ranges such as "Wa to We", never "Other"), A to Z, split chosen for the lowest expected items heard. Slow on a phone: built off the main thread (`ModeOutput.background`). Used by `ControllerMode` (Screen.FIND). |
| NameLang.kt | `NameLang.of(pkg, label)` = "es" or "en" for an app name (package table, then Spanish words/letters, else en); `voice()` = "es" or null. Used by `ControllerMode.treeLabel` (tree, Find, Suggested). |
| Calculator.kt | `Calculator` = state of the spoken calculator (digit, point, operate, equals, undo, `spoken()`), left to right; `%` = percent of. Used by the Calculator program. |
| PriceSum.kt | `PriceSum` = running total of prices in cents (`add("12.5")`, `undo()`, `spoken()`). Used by the Sum prices tool (`launchSum` in Actions). |
| Calls.kt | `Calls.kind(category, callType, actions)` = a dialer notification is INCOMING, ONGOING or NONE. `callerName`, `spokenNumber`, `rank(contacts)` (starred, then most called, one per number), `withTop(top, rest)` (3 most called first, then `rank`), `topNumbers(log)` (most frequent numbers of a call log), `emergencyNumber(country)` (112 fallback), `emergencyText()`. Used by the call screens in `ControllerMode` and the listener. |
| AiAssist.kt | `AiAssist.isLong(text)` (> 40 words: a short version is read first), `digestKey(items, hidden)` (cache key of a set of notifications), `digestable(n)`, `withPrivate(line, hidden)` (adds ", 2 private" so the digest numbers add up to all). Used by `AiHelp` (:os) and `ControllerMode`; the model runs on the laptop bridge (`/ai/digest`, `/ai/short`). `earlier(current, all)` (the 2-3 earlier messages of the same chat as context, never a private one). |
| AiChoice.kt | `AiChoice.pick(enabled, computerSet, computerDown, phoneModel)` = the ONE order for every AI job (computer, else phone model, else none; switch off = none) and `label(...)` = the subtitle of the AI row ("Computer", "Phone", "Phone, model not downloaded", "Off"). Used by `AiHelp` and the settings. |
| Completion.kt | `Completion.fits(text, typed)` = does a whole text fit the letters typed (the typed words start the text, or the typed letters are the first letters of its words: "hay" -> "how are you"). Same rule as `completion_fits` in the bridge. Used by `AiGuard.makeCompletions` and Write (`Mixer`, `ListSource`). |
| AiGuard.kt | The accuracy guard and prompts of the notification AI, the same as the bridge (`story.check_story`, `aitext.py`): `checkStory`, `makeShort(text, context, model)`, `makeDigest(items, model)`, `makeCompletions(typed, context, lang, model)` (the Write "complete" job, bridge `/ai/complete`), `earlierBlock`. Pure; the model is a function. Keep in step with the bridge. Used by `AiHelp` for the phone model. |
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
| What is said when a chat message arrives, and one reading per burst | `ArrivalPlan.lines`, `ArrivalBurst` | logickit/Arrival.kt (the OS speaks it: `Actions.arrival`) |
| Which apps are live (go first on home) | `LiveApps.pick` | os/LiveApps.kt |
| Answer a notification (fire an action, reply, dismiss, mute) | `ControllerNotificationListener.fireAction / sendReply` | ControllerNotificationListener.kt |
| Where an app sits in the spoken tree | `AppCategories`, `AppWeights`, `AppTree`, `TreeLeaves` (edits in `LauncherSettings`) | those files |
| Which apps exist and how often they were opened | `DeviceApps` | DeviceApps.kt |
| Tasks, and offering past texts again | `TaskStore`, `PhraseMemory` | TaskStore.kt, PhraseMemory.kt |
| What to offer first in the hub | `Suggester` | Suggester.kt |
| The place Write types into (app, field kind, screen text) | `Frame`, `BucketStore`, `BucketRanker` | Context.kt |
| Next word (English) | `NextWord` | NextWord.kt |
| Phone AI (replies, endings, tasks, raw `generate`) | `LocalAi`, `ModelDownload`; jobs go through `AiHelp` (computer first, phone fallback) | LocalAi.kt, ModelDownload.kt, AiHelp.kt |
| Weather and navigation sentences | `Weather`, the nav parser | Info.kt |
| Local choice log and gesture log | `ChoiceLog`, `EventLog` | ChoiceLog.kt, EventLog.kt |
| Earbud clicks while the OS is closed -> next/previous/play-pause for the real player; which player; who pauses on wake | `MediaReplay` (used by `Actions.forwardToMusic / pauseMusicNow`) | MediaReplay.kt |
| The voice speed the engine uses (slider + use bonus, max 1.8) | `SpeechRate.effective` (used by `Actions.speechRate`) | SpeechRate.kt |
| Speak, play, read the button, a list | the **Voice Kit** | voicekit/KIT.md |
| Colors, rows, switches | the **UI Kit** | Ui.kt |

## Add something
1. Search this table and `krn find` for it. Reuse if it exists.
2. If you copy the same lines a second time, move them here, make the call sites use it, and add a row.
3. Pure logic only (no Android, no I/O, no speech): it must have a unit test. Anything that touches Android goes to an engine/door that already exists, or is a new row in the table.
4. If a duplicate has a clear text pattern, add a `logic-` rule to `voicekit-rules.tsv` and its test line in `KitRulesTest`.
