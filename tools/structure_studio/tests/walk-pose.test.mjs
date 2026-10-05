import test from 'node:test';
import assert from 'node:assert/strict';
import {mat4} from 'gl-matrix';
import {walkPose} from '../web/walk-pose.js';
function usable(pose) {
  const {eye,yaw,pitch}=pose;
  const at=[eye[0]+Math.sin(yaw)*Math.cos(pitch),eye[1]+Math.sin(pitch),eye[2]+Math.cos(yaw)*Math.cos(pitch)];
  const view=mat4.lookAt(mat4.create(),eye,at,[0,1,0]);
  assert.ok([...view].every(Number.isFinite));
  assert.ok(Math.abs(mat4.determinant(view))>.9);
}
test('standing work point keeps facing when approach equals position',()=>{
 const pose=walkPose({pos:[4,2,8],approach:[4,2,8],facing:'east'},[20,20,20]);
 assert.equal(pose.pitch,0);assert.equal(pose.yaw,-Math.PI/2);usable(pose);
});
test('explicit vertical gaze cannot collapse the view matrix',()=>{
 for(const y of [-100,100])usable(walkPose({pos:[4,2,8],look_at:[4,y,8]},[20,20,20]));
});
test('separate interaction target still directs the camera',()=>{
 const pose=walkPose({pos:[8,3,8],approach:[4,2,8]},[20,20,20]);
 assert.equal(pose.yaw,Math.PI/2);usable(pose);
});
