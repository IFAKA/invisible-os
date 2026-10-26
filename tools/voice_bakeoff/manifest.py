"""The fixed spoken sentences (lang, text). Frozen by hand from harvest.py; extend when the app gets a new fixed sentence.
Phone-side misses are logged in files/spoken.log: add them here, rerun make_clips.py (it only makes what is missing)."""
import hashlib
def key(lang, text): return hashlib.sha1(f"{lang}|{text.strip()}".encode()).hexdigest()[:12]   # same rule as ClipBank.kt
EN = """Two clicks when you hear it. One click: next. Three clicks: go back.
In the menu: click when you hear the option. 2 = one back, 3 = back, 4 = leave. Hold = exit.
Timer done|Alarm stopped|Flashlight on|Flashlight off|Do not disturb on|Do not disturb off|No new open tasks|1 task added|No tasks yet|All done|Task added
Buy groceries|Call someone|Pay a bill|Send an email|Clean up|No unread messages|Cannot do it|Cancelled
Open the app and allow location.|No weather now.|Open the app and allow the calendar.|Cannot read the calendar.|No events today.|Event|Find my phone|No navigation.
no rain soon|raining now|Level one. Jab and guard.|To leave, hold three seconds.|Tap to jab.|Listen for a click.|Hold to the click to guard.|Guard beats jab.
Level two. Charge.|Hold longer.|Two clicks.|Let go after the second.|Charge beats guard.|Jab beats charge.|No charge twice in a row.
Level three. Energy.|Charge costs three.|Listen.|More energy, higher note.|You get one back each round.|Jab.|Guard.|Charge.|Not again.|Let's move on.
Hold three seconds to leave.|Press to continue.|Press the button.|Last life.|Their last life.|Tap quickly.|Hold for the first click.|Wait for the second click.
Loading the duel|Duel could not start|Parry.|Parried.|Fizzle.|Hit.|Ouch.|Nothing.|Trade.|Charge broken.|Their charge broken.|Both hurt.|Big hit.|Big ouch.|Charge ready.|Low energy.
Write. Click on the first word.|Hold on the second word.|Two clicks for the next two.|Three clicks for more.|Done|Delete|Write could not start|Nothing written|Deleted|Letters|Words|Pick the left or the right
Torch|Do not disturb|Title, Artist|How to play|Suggested|Playing|Tasks, none|Timer|Status|Info|Phone|Games|Add task|More tasks|Done. Click to cancel|Last used|Louder|Quieter|Weather|Next event|Next turn|Back|Volume
Duel|Write|Stop|Five more minutes|Ignore|Read all|Compose|Task|Reply okay|Reply yes|No|Nothing|Skip|More|Ask details|Sending. Click to cancel|Starting. Click to cancel|Click to cancel
YouTube|Instagram|Facebook|TikTok|Spotify|Maps|Invitation|Question|Request|Thanks|Message|Yes|Maybe|Later|Cant now|Youre welcome|Ok|Call me|Today|Tonight|Tomorrow|Weekend|Sorry
What time and where?|Sure, count me in|Sure, today works|Sure, tonight works|Sure, tomorrow works|Sure, the weekend works|I can't|Can't today|Can't tonight|Can't today, tomorrow works|Can't today, the weekend works
Maybe today|Maybe tonight|Maybe tomorrow|Maybe the weekend|I'll tell you later|I'll tell you today|I'll tell you tonight|I'll tell you tomorrow|I'll tell you over the weekend
Can't right now, I'll text you later|You're welcome|Call me when you can|Time|Next alarm|No alarm|1 unread message|no unread messages|Messages|Tasks|Read""".replace("\n", "|").split("|")
ES = """Responder ok|Responder sí|Leer todo|Redactar|Nada|Saltar|Más|Invitación|Pregunta|Pedido|Gracias|Mensaje|Sí|Quizás|Luego|Ahora no|De nada|Llámame|Hoy|Esta noche|Mañana|Fin de semana|Omitir|Perdón
¿A qué hora y dónde?|Sí, dale|Sí, hoy va bien|Sí, esta noche va bien|Sí, mañana va bien|Sí, el fin de semana va bien|No puedo|Hoy no puedo|Esta noche no puedo|Hoy no puedo, mañana sí|Hoy no puedo, el fin de semana sí
Quizá|Quizá hoy|Quizá esta noche|Quizá mañana|Quizá el fin de semana|Te digo luego|Te digo hoy|Te digo esta noche|Te digo mañana|Te digo el fin de semana|Ahora no puedo, te escribo luego|Llámame cuando puedas""".replace("\n", "|").split("|")
NUM = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty".split()
def all_items():
    en = list(EN) + [f"Timer {m} minutes" for m in range(1, 61)] + [f"Battery {p} percent" for p in range(101)]
    en += [f"{n} tasks added" for n in range(2, 21)] + [f"{n} unread messages" for n in range(2, 31)]
    en += [f"{w.capitalize()} minute{'' if w == 'one' else 's'}" for w in NUM[1:]] + [f"Rung {w}." for w in NUM[:6]]
    en += [f"First match. Rung {w}." for w in NUM[:6]] + [f"Duel. Rung {w}." for w in NUM[:6]] + [f"Your life {w}." for w in NUM[:6]] + [f"Their life {w}." for w in NUM[:6]]
    en += [f"{who} {mv}." for who in ("They", "You", "Both") for mv in ("jab", "guard", "charge")] + [f"That was a {mv}." for mv in ("jab", "guard", "charge")]
    out, seen = [], set()
    for lang, ts in (("en", en),):  # Spanish quick replies removed: input text is open-ended
        for t in ts:
            t = t.strip()
            if t and (lang, t) not in seen: seen.add((lang, t)); out.append((lang, t))
    return out
if __name__ == "__main__":
    it = all_items(); print(len(it), "sentences;", sum(l == "es" for l, _ in it), "Spanish"); print(key("en", "Timer done"))
