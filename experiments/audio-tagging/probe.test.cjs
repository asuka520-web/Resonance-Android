'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {decodeWav,analyze}=require('./probe.cjs');
function wav(rate=16000) {
  const b=Buffer.alloc(44+48000*2);
  b.write('RIFF');b.writeUInt32LE(b.length-8,4);b.write('WAVE',8);b.write('fmt ',12);b.writeUInt32LE(16,16);
  b.writeUInt16LE(1,20);b.writeUInt16LE(1,22);b.writeUInt32LE(rate,24);b.writeUInt32LE(rate*2,28);
  b.writeUInt16LE(2,32);b.writeUInt16LE(16,34);b.write('data',36);b.writeUInt32LE(48000*2,40);
  return b;
}
test('accepts exact input format and rejects unsupported or truncated audio',()=>{
  assert.equal(decodeWav(wav()).length,48000);
  assert.throws(()=>decodeWav(wav(44100)),/Convert input/);
  assert.throws(()=>decodeWav(wav().subarray(0,50)),/Truncated/);
  assert.throws(()=>decodeWav(wav(),2,30),/three seconds/);
});
test('silence returns no inferred labels and needs no model or network',async()=>{
  const result=await analyze(new Float32Array(48000),'not-read','not-read');
  assert.equal(result.status,'insufficient_signal');assert.deepEqual(result.predictions,[]);
  assert.equal(result.verified,false);assert.equal(result.updatesCatalogue,false);
});
