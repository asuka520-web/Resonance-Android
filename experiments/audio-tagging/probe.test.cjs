'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {decodeWav,analyze,measureRhythm,assessTempo,summarizeTrackTempo}=require('./probe.cjs');
function wav(rate=16000) {
  const dataBytes = 3 * rate * 2;
  const b=Buffer.alloc(44+dataBytes);
  b.write('RIFF');b.writeUInt32LE(b.length-8,4);b.write('WAVE',8);b.write('fmt ',12);b.writeUInt32LE(16,16);
  b.writeUInt16LE(1,20);b.writeUInt16LE(1,22);b.writeUInt32LE(rate,24);b.writeUInt32LE(rate*2,28);
  b.writeUInt16LE(2,32);b.writeUInt16LE(16,34);b.write('data',36);b.writeUInt32LE(dataBytes,40);
  return b;
}
test('accepts exact input format and rejects unsupported or truncated audio',()=>{
  assert.equal(decodeWav(wav()).length,48000);
  assert.throws(()=>decodeWav(wav(44100)),/Convert input/);
  assert.equal(decodeWav(wav(44100),0,30,44100).length,132300);
  assert.throws(()=>decodeWav(wav().subarray(0,50)),/Truncated/);
  assert.throws(()=>decodeWav(wav(),2,30),/three seconds/);
});

test('tempo stays unknown for silence and clips too short for reliable beat tracking',()=>{
  const signal = decodeWav(wav(44100),0,30,44100);
  assert.equal(measureRhythm(signal).status,'insufficient_signal');
  signal.fill(0.5);
  const report = measureRhythm(signal);
  assert.equal(report.status,'clip_too_short_for_tempo');
  assert.equal(report.tempoBpm,null);
  assert.ok(Math.abs(report.rmsDbfs + 6.02) < 0.01);
});
test('silence returns no inferred labels and needs no model or network',async()=>{
  const result=await analyze(new Float32Array(48000),'not-read','not-read');
  assert.equal(result.status,'insufficient_signal');assert.deepEqual(result.predictions,[]);
  assert.equal(result.verified,false);assert.equal(result.updatesCatalogue,false);
});

test('only two agreeing tempo estimates produce a BPM usable for comparison', () => {
  assert.equal(assessTempo(120, 122).tempoBpm, 120);
  const octave = assessTempo(178.2, 88);
  assert.equal(octave.status, 'ambiguous_half_double_tempo');
  assert.deepEqual(octave.tempoCandidates, [88, 178.2]);
  assert.equal(octave.tempoBpm, null);
  assert.equal(assessTempo(120, 92).status, 'uncertain_tempo');
  assert.equal(assessTempo(120, 0).tempoBpm, null);
});

test('a second excerpt can support one candidate, but two ambiguous excerpts stay unknown', () => {
  const split = assessTempo(136, 68);
  const confirmed = assessTempo(135, 136);
  assert.equal(summarizeTrackTempo(split, confirmed), 135.5);
  assert.equal(summarizeTrackTempo(confirmed, split), 135.5);
  assert.equal(summarizeTrackTempo(split, split), null);
  assert.equal(summarizeTrackTempo(confirmed, assessTempo(90, 90)), null);
});
