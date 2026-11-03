**English** | [简体中文](guidance-operating-envelope.zh-CN.md)

# Guidance operating envelope

What Guidance cannot see or cannot judge today, and why, so that having no prompt is read for what it is.

**No prompt does not mean the way is clear.** It means that nothing Guidance can recognise has met the conditions
for a prompt. The cases below are the ones where that matters most.

## What it reasons from

- **Obstacles** come from an object detector whose classes are a fixed list. What is not on the list is not an
  obstacle to it, however large.
- **The walkable ground** comes from a semantic segmentation model that labels every pixel with one of its dataset's
  classes, including things it has never seen, which get the nearest class it knows.
- **Distance** comes from the bottom of a box, or from where that bottom meets the ground, given the phone's tilt and
  height. It says nothing about anything that has no box.

## Known limits

| What | What happens | Why |
|---|---|---|
| Water: a pool, a pond, standing water | Often read as ground, so no warning | Reflective, flat, with no class of its own to set it apart from a road or floor |
| Sculptures, installations, unusual objects | Often no prompt | Not among the detector's classes; the segmentation model gives them whatever class is nearest |
| Glass doors and walls | Often no prompt | The model sees what is behind the glass |
| Steps, curbs and drops | No dependable prompt | Nothing looks for a change in height. A drop looks like more ground, and a low step has no box |
| Reflective indoor floors | Ground may go unrecognised; path prompts then pause, and Guidance says it cannot tell whether the way is clear | The segmentation model was not trained on them |
| Anything above the phone's view, or below it | No prompt | Out of frame: an overhanging branch, a low bollard close in front |

## Reading the prompts

- With path-complex prompts off, only obstacles and a blocked path are announced, in speech and in vibration. A run
  says so once when it starts, unless speech is off. Some of the "ahead" vibrations used to come from path-complex
  prompts, and no longer do.
- "Blocked" is said only when several signs agree. A path it does not call blocked may still be impassable.
- A covered lens, low light and unrecognised ground are told as such, because then even the absence of other prompts
  says little.
- A distance is an estimate from one camera. It leans towards nearer, and it depends on the phone being held about as
  the settings say.
