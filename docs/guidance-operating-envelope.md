**English** | [简体中文](guidance-operating-envelope.zh-CN.md)

# Guidance operating envelope

What Guidance cannot see or cannot judge today, and why; the conditions it has been tried under; and what you hear and
feel when Guidance itself stops working. All of it so that having no prompt is read for what it is.

**No prompt does not mean the way is clear.** It means that nothing Guidance can recognise has met the conditions
for a prompt. The cases below are the ones where that matters most.

## What it reasons from

- **Obstacles** come from an object detector whose classes are a fixed list. What is not on the list is not an
  obstacle to it, however large.
- **The walkable ground** comes from a semantic segmentation model that labels every pixel with one of its dataset's
  classes, including things it has never seen, which get the nearest class it knows.
- **Distance** comes from the bottom of a box, or from where that bottom meets the ground, given the phone's tilt and
  height. It says nothing about anything that has no box.

This is the Standard mode, the default. In the Basic mode, chosen in Settings, the object detector does not run:
obstacles are read from the segmentation instead, so the detector's list of classes does not apply. **Not verified**:
every field recording used the Standard mode.

## Operating conditions

Each condition has one of three statuses:

- **Supported**: Guidance is made for it, and it has been tried in field recordings.
- **Not supported**: Guidance does not do it. Do not rely on it there.
- **Not verified**: how Guidance behaves there has not been measured. It may work or it may not.

What Guidance *does* in each case is how the code behaves. The status says only whether that has been tried in the
field.

### Carrying the phone

- **Supported: held upright in the hand at chest height, the back camera facing the way ahead.** This is how every
  field recording was made. In most of them the phone was tilted about 14–19° down, taking each recording's median.
- **Not verified: any other way of carrying it**: on a lanyard, at the waist, in a pocket or a bag, sideways
  (landscape), tilted far up or down.
- **How distance is worked out.** How near an obstacle is comes from how low the bottom of its box sits in the
  picture. In the lowest quarter it counts as near, and its prompt starts with "Careful". In the upper 45% it counts
  as far, and a far obstacle is usually not announced at all. In between it is the middle level. The phone's height
  plays no part in it, so how the phone is tilted and held changes the level. In the simulator, with one fixed
  camera and scene, a person standing 3 m away comes out at the middle level or far depending only on the phone's
  tilt and height.
- **The carrying settings are not in the normal app.** Chest lanyard, in the hand at chest height and at the waist
  can be chosen only on a debug build's diagnostics screen, together with an experimental ground-contact distance.
  That distance is off by default and stays off in a release build. Until a placement is chosen, the phone height is
  taken as 1.3 m, and with that height the ground-contact distance changes no level even when it is on. When it is on
  with a placement, it can only make a level nearer, never farther.
- **No prompt here means:** nothing outside the camera's view is seen: above the phone, below it, or to the side.

### Moving

- **Supported: walking.** Every field recording was made walking or standing still.
- **Not supported: running, and any way of moving other than walking.**
- **No prompt here means:** at a higher speed, an obstacle can be reached before a prompt about it is given.

### Light and weather

- **Supported: outdoors on pavements, shopping streets and at road crossings; indoors in shopping-mall corridors and
  entrances.** These are where the field recordings were made.
- **Low light.** Below a fixed brightness Guidance says "Too dark to see" and gives no new prompts about the scene
  until the light is back and the pictures have been judged again (see [When Guidance itself is not working](#when-guidance-itself-is-not-working)).
- **A very even picture counts as a covered lens**, and that is checked before darkness: a picture that is uniformly
  dark, or so blurred that it is uniform, can be reported as covered rather than as too dark.
- **Not verified: dim light above that threshold**, such as dusk or street lighting at night.
- **A hand over the lens in bright sunlight** may not be recognised as a covered lens: light coming through the hand
  passes the brightness check. This was seen in a field pilot recording.
- **Not verified: rain, fog, snow, water drops or dirt on the lens.** Only the picture's brightness and how evenly it
  is spread are checked. There is no alert for a blurred or smeared picture as such.
- **No prompt here means:** with a wet, dirty or misted lens the models may see nothing at all, and nothing tells you.

### Steps, crossings and crowds

- **Not supported: stairs, curbs and drops.** Nothing looks for a change in height. In the simulator a 15 cm curb
  down to the road gives no prompt, and walking towards a 1.5 m drop gives none either.
- **Not supported: crossing a road.** Guidance finds no crossings and no crosswalks, does not read whether a light is
  red or green, and does not warn about traffic on the road (road warnings are off). It may say "Traffic light ahead"
  when it sees one, as a low-priority prompt. A vehicle in the walking path is still announced as an obstacle.
  Waiting at a crossing was part of the field recordings.
- **Supported: moderately busy pavements and shopping streets**, with people coming towards you or crossing your path.
- **Not verified: dense crowds.** Each detection returns at most 10 obstacles of every kind together: vehicles and
  other obstacles take places too.
- **No prompt here means:** at a crossing, nothing has been judged about the traffic. In a crowd, not everyone in view
  is considered.

### Earphones and screen readers

- **Speech** plays as accessibility audio, asking other audio to lower its volume for a moment. It is spoken even
  when that request is refused. Android chooses where it goes.
- **Not verified: wired or Bluetooth earphones.** Nothing notices when earphones are disconnected or when speech goes
  somewhere you cannot hear it.
- **Screen reader** (such as TalkBack) on, with speech on: every prompt and alert is handed to the screen reader, in
  its voice and at its rate, and Guidance's own speech is not started. For a covered lens, low light and unrecognised
  ground, handing the alert to the screen reader counts as telling you: the app cannot know whether you heard it, so
  the vibration that otherwise follows such an alert not heard in time does not come, and with fault vibration off it
  has no vibration at all. Stalled, failed and interrupted Guidance still give the interruption vibration first,
  whatever the vibration setting. With speech off, the screen
  reader is not used for prompts: they are given by vibration only, or, with navigation vibration off too, only on
  the screen. **Not verified** in the field: the recordings were made with the screen reader off.
- **No prompt here means:** if the audio goes somewhere you cannot hear, prompts are lost and nothing says so.

### Phones and accelerators

- Android 12 or later, on a 64-bit ARM processor.
- **Supported: Snapdragon SM8850** (OnePlus CPH2747, Android 16), every model on the GPU through LiteRT 2.1.5. All
  field recordings were made on it, at about 20 analysed frames a second.
- **Snapdragon SM8450** (Samsung SM-S9010): the field capture's timing was measured on it. On it, as on SM8850, model
  files from one export tool computed wrongly on the GPU without any error.
- **Not verified: every other phone.**
- A model that runs on the GPU without an error can still give wrong answers there. After changing the model files,
  the LiteRT version or the phone, run the model consistency check described in [models.md](models.md). Guidance does
  not switch to the CPU by itself.

### Screen off and background

- While Guidance runs, the screen is kept on.
- If the screen is turned off, or the app goes to the background (the Home button, a call, another app), **the camera
  stops and Guidance detects nothing.** The session is not ended: you feel the interruption vibration (three extra
  long) whatever the vibration setting, and, with speech on, hear "Guidance interrupted". When the app is back in
  front, Guidance resumes by itself and allows 20 s for the first result before it reports anything. Tried on SM8850.
- **From the moment Guidance learns that the app went to the background** (Android tells it a moment after you switch
  away), no prompt is given, not even about a picture still being analysed then. Back in front, prompts return once
  the camera sends pictures again and about half a second of new pictures has been judged.
- **No prompt here means:** while the app is in the background or the screen is off, nothing is detected at all.

## Not supported

Guidance does not do any of these. They are not ways of using it that have yet to be tested:

- running;
- guidance on stairs, curbs and drops;
- guidance for crossing a road;
- telling you which way to go. Prompts say what is where, and that a path is blocked; they never say "go left" or
  "go right";
- the ground-contact distance in any way of carrying the phone. Its placement heights are proposals that have not
  been calibrated, and no way of carrying has been verified with it.

## When Guidance itself is not working

What you hear and feel when Guidance cannot judge the way, or cannot tell you. "Checked on a phone" means the alert
has been compared on a phone; "in tests" means it cannot be produced on purpose on a phone and is checked only by
automated tests of the code that gives it.

| What happened | What you hear | What you feel | What else happens | Checked |
|---|---|---|---|---|
| Lens covered, or a plain wall about 30 cm ahead | "Camera is covered, or too close to a wall", again every 8 s. Once the view has been back for 2 s, Guidance tries to say "Camera is clear again" | The "can't see" vibration (long, short, long) each time, if fault vibration is on; with speech off, that vibration is the alert, every 8 s, and cannot be turned off | No new prompts about the scene; once the view is back, prompts return only after at least three pictures spanning about half a second have been judged (longer when pictures come slowly). If the sentence has not been heard within 5 s, the vibration comes anyway, whatever the settings | On a phone |
| Too dark | "Too dark to see", again every 15 s. Once the light is back, Guidance tries to say "Light is back to normal" | The "can't see" vibration when it starts, if fault vibration is on; with speech off, every 15 s | No new prompts about the scene; once the light is back, prompts return only after at least three pictures spanning about half a second have been judged (longer when pictures come slowly). If not heard within 6 s, the vibration comes anyway | On a phone |
| Ground not recognised for 1.5 s | "Can't see the ground, can't tell if the way ahead is clear", again every 60 s. Once ground has been recognised for 1 s, Guidance tries to say "Ground recognized again, path alerts are back" | The "can't see" vibration when it starts, if fault vibration is on; with speech off, every 60 s | "Path blocked" and other path prompts pause; obstacles are still announced. If not heard within 10 s, the vibration comes anyway | On a phone |
| No result for 2.5 s while running, or none 20 s after starting | "Guidance is not responding. Stop and check before moving on.", again every 10 s | The interruption vibration (three extra long), whatever the vibration setting | No new prompts about the scene until results come again and about half a second of new pictures has been judged; a picture taken before the stall does not bring them back | In tests |
| Analysis failed: 30 frames in a row, or the models could not load | "Guidance stopped: analysis failed. Do not rely on it until restarted." | The interruption vibration, whatever the vibration setting | All other output stops first; the session ends and shows an error. If frames fail slowly, "not responding" can come first | In tests |
| App in the background or screen off | "Guidance interrupted" | The interruption vibration, whatever the vibration setting | See [Screen off and background](#screen-off-and-background) | On a phone |
| The phone's own speech engine stops working, while you rely on it | Nothing: it cannot speak | One "no speech" vibration (short, short, extra long) per outage, whatever the vibration setting | Prompts are given by vibration if navigation vibration is on; if it is off, they are not given at all. "Can't see" alerts fall back to their vibration | In tests |
| The phone has no vibration motor | — | Nothing: no vibration is felt, and nothing says so | — | Not checked |
| Earphones disconnected, or speech goes somewhere you cannot hear | — | — | Nothing notices it | Not checked |

- **No new prompts** means that nothing new about the scene is given while the alert holds, nor until the pictures
  have been judged again as above. A prompt already being spoken or vibrated when it began may carry on, and can keep a
  less urgent alert waiting; one still waiting for the vibration motor is withdrawn.
- **The status card** on the screen shows the stop while it holds (the alert's own sentence), then "Checking the view"
  until prompts return. While the ground is not recognised it shows "Can't see the ground, can't tell if the way ahead
  is clear" for as long as that lasts, unless a prompt about the scene, such as an obstacle, is current. From pressing
  start until the first picture has been judged it shows "Starting guidance". It does not say "Path looks clear" in
  any of these.
- **A recovery sentence may not be heard.** It is a low-priority try: if no speech takes it within 5 s it is dropped,
  and once taken a more urgent prompt can cut it off. With speech off there is no recovery sentence and no recovery
  vibration.
- **With a screen reader on and speech on**, every sentence above goes to the screen reader instead. For the first
  three rows (covered lens, too dark, ground not recognised), handing it over counts as telling you, so the "comes
  anyway" vibration does not follow, and with fault vibration off there is no vibration at all. Not responding,
  analysis failed and background still give the interruption vibration before the sentence, whatever the vibration
  setting.

## Known limits

| What | What happens | Why |
|---|---|---|
| Water: a pool, a pond, standing water | Often read as ground, so no warning | Reflective, flat, with no class of its own to set it apart from a road or floor |
| Sculptures, installations, unusual objects | Often no prompt | Not among the detector's classes; the segmentation model gives them whatever class is nearest |
| Glass doors and walls | Often no prompt | The model sees what is behind the glass |
| Steps, curbs and drops | No dependable prompt | Nothing looks for a change in height. A drop looks like more ground, and a low step has no box |
| Reflective indoor floors | Ground may go unrecognised; path prompts then pause, and Guidance says it cannot tell whether the way is clear | The segmentation model often gives them a class other than ground |
| Anything above the phone's view, or below it | No prompt | Out of frame: an overhanging branch, a low bollard close in front |
| A picture that reaches Guidance late | Judged as if it were current: a prompt can describe where things were. Except after Guidance had to stop: a picture taken before the stop does not bring prompts back | Nothing checks how old a picture is |
| A camera that keeps sending the same picture | Guidance goes on judging the old picture, with no alert | Only the absence of results is noticed, not a picture that stops changing |

## Reading the prompts

- With path-complex prompts off, no path-complex prompt is given, in speech or in vibration; obstacles, a blocked path
  and the other kinds of prompt, such as "Traffic light ahead", still follow their own rules. A run says "Now alerting
  for obstacles and blocked paths only" once when it starts, unless speech is off. Some of the "ahead" vibrations used
  to come from path-complex prompts, and no longer do.
- "Blocked" is said only when several signs agree. A path it does not call blocked may still be impassable.
- A covered lens, low light and unrecognised ground are told as such, because then even the absence of other prompts
  says little.
- A plain wall about 30 cm ahead looks to the camera like a covered lens, and a tissue over the lens looks the same, so
  the covered-lens alert says "covered, or too close to a wall". As for a covered lens, no new prompts about the scene
  are given while it holds.
- A distance is a coarse level from one camera, read from where the obstacle's box sits in the picture. It changes
  with how the phone is tilted and held; see [Carrying the phone](#carrying-the-phone).
