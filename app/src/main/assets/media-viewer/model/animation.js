// Animation rig: wraps THREE.AnimationMixer for the panel's clip controls.
// SolidWorks XR exports carry exploded views as glTF keyframe clips — the
// scrub bar doubles as the "explode slider" by construction.

import * as THREE from "three";

const EXPLODE_PATTERN = /explode|assembl/i;

export class AnimRig {
  constructor(root, clips) {
    this.root = root;
    this.clips = clips || [];
    this.mixer = this.clips.length ? new THREE.AnimationMixer(root) : null;
    this.index = 0;
    this.action = null;
    this.playing = false;
    this.loop = false;
    if (this.mixer) this.setClip(0);
  }

  get hasClips() { return this.clips.length > 0; }
  get clip() { return this.clips[this.index] || null; }
  get duration() { return this.clip ? this.clip.duration : 0; }

  clipLabel() {
    const clip = this.clip;
    if (!clip) return "";
    const name = (clip.name || "").trim();
    if (!name || EXPLODE_PATTERN.test(name)) return "EXPLODE";
    return name.toUpperCase();
  }

  setClip(index) {
    if (!this.mixer || !this.clips.length) return;
    const total = this.clips.length;
    this.index = ((index % total) + total) % total;
    if (this.action) this.action.stop();
    this.action = this.mixer.clipAction(this.clips[this.index]);
    this.applyLoop();
    // Pose at frame zero, paused: the panel owns when playback starts.
    this.action.play();
    this.action.paused = true;
    this.action.time = 0;
    this.mixer.update(0);
    this.playing = false;
  }

  applyLoop() {
    if (!this.action) return;
    if (this.loop) {
      this.action.setLoop(THREE.LoopRepeat, Infinity);
      this.action.clampWhenFinished = false;
    } else {
      // Default: play once and HOLD — an exploded assembly stays exploded.
      this.action.setLoop(THREE.LoopOnce, 1);
      this.action.clampWhenFinished = true;
    }
  }

  setLoop(loop) {
    this.loop = !!loop;
    this.applyLoop();
  }

  fraction() {
    if (!this.action || !this.duration) return 0;
    return Math.max(0, Math.min(1, this.action.time / this.duration));
  }

  setFraction(fraction) {
    if (!this.action || !this.duration) return;
    this.playing = false;
    this.action.paused = true;
    if (!this.action.enabled) this.action.enabled = true;
    this.action.time = Math.max(0, Math.min(1, fraction)) * this.duration;
    this.mixer.update(0);
  }

  togglePlay() {
    if (!this.action) return;
    if (this.playing) {
      this.playing = false;
      this.action.paused = true;
      return;
    }
    // Re-arm a finished one-shot: play from the start (or from a mid-scrub).
    if (!this.loop && this.fraction() >= 0.999) this.action.time = 0;
    this.action.enabled = true;
    this.action.paused = false;
    this.playing = true;
  }

  update(dt) {
    if (!this.mixer || !this.playing) return;
    this.mixer.update(dt);
    if (!this.loop && this.fraction() >= 0.999) {
      this.playing = false;
      this.action.paused = true;
    }
  }

  dispose() {
    if (!this.mixer) return;
    this.mixer.stopAllAction();
    this.mixer.uncacheRoot(this.root);
    this.mixer = null;
    this.action = null;
    this.clips = [];
  }
}
