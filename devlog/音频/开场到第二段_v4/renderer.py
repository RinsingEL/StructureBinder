"""Offline renderer extracted without musical changes from the v2 study.

The composition sets PARTS, NOTES, DURATION and curve before rendering.
Uses installed environment packages; the local package directory is optional.
"""
from pathlib import Path
import sys, json, math, wave, subprocess
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RT = ROOT / '.tools/devlog-audio'
sys.path.insert(0, str(RT / 'python'))
import numpy as np
from scipy import signal
import tinysoundfont, imageio_ffmpeg
SR = 48000
SF = RT / 'soundfonts/GeneralUser-GS.sf2'
FF = imageio_ffmpeg.get_ffmpeg_exe()
DURATION = 0
PARTS = {}
NOTES = {}
def curve(name, times):
    return np.ones_like(times)

def compressor(a,threshold=-25,ratio=1.6,knee=6,attack=.018,release=.15):
    # Stereo-linked RMS detector, 1 ms control rate, smooth attack/release.
    hop=48;n=len(a);pad=(-n)%hop
    x=np.pad(a,((0,pad),(0,0))).reshape(-1,hop,2)
    level=10*np.log10(np.maximum(np.mean(x*x,axis=(1,2)),1e-12))
    delta=level-threshold
    wanted=np.where(delta<-knee/2,0,np.where(delta>knee/2,delta*(1-1/ratio),
                      (delta+knee/2)**2/(2*knee)*(1-1/ratio)))
    aa=math.exp(-.001/attack);ar=math.exp(-.001/release)
    reduction=np.empty_like(wanted);state=0
    for i,v in enumerate(wanted):
        coef=aa if v>state else ar;state=coef*state+(1-coef)*v;reduction[i]=state
    gain=10**(-np.interp(np.arange(n),np.arange(len(reduction))*hop,reduction)/20)
    return (a*gain[:,None]).astype(np.float32),float(reduction.max())

def render(name,shaped):
    program,ch,pan,target,send=PARTS[name]
    synth=tinysoundfont.Synth(gain=-9,samplerate=SR);sid=synth.sfload(str(SF))
    synth.program_select(ch,sid,128 if ch==9 else 0,program,is_drums=ch==9)
    synth.control_change(ch,10,64)
    n=round(DURATION*SR);out=np.zeros((n,2),np.float32);events=[]
    for t,p,d,v in NOTES[name]:
        if not shaped and name in ['claude_clarinet','astra_epiano']:v=62
        events.extend([(round(t*SR),1,p,v),(round((t+d)*SR),0,p,0)])
    pos=0
    for at,kind,p,v in sorted(events)+[(n,2,0,0)]:
        at=min(at,n)
        if at>pos:out[pos:at]=np.frombuffer(synth.generate(at-pos),np.float32).reshape(-1,2);pos=at
        if kind==1:synth.noteon(ch,p,v)
        elif kind==0:synth.noteoff(ch,p)
    cutoff=36 if name=='shared_bass' else 85
    out=signal.sosfilt(signal.butter(2,cutoff,'highpass',fs=SR,output='sos'),out,axis=0).astype(np.float32)
    out=signal.sosfilt(signal.butter(2,6500,'lowpass',fs=SR,output='sos'),out,axis=0).astype(np.float32)
    pow=np.mean(out[:len(out)//4800*4800].reshape(-1,4800,2)**2,axis=(1,2))
    rms=np.sqrt(np.mean(pow[pow>max(pow.max()*.012,1e-12)]))
    out*=10**(target/20)/max(rms,1e-9)
    reduction=0
    if shaped:
        out*=curve(name,np.arange(n)/SR).astype(np.float32)[:,None]
        if name in ['astra_epiano','shared_guitar','shared_bass']:
            out,reduction=compressor(out,threshold=-25 if name=='astra_epiano' else -31)
    out[:,0]*=math.sqrt(1-pan);out[:,1]*=math.sqrt(1+pan)
    return out,send,reduction

def writewav(path,a):
    assert np.isfinite(a).all() and np.max(np.abs(a))<1
    with wave.open(str(path),'wb') as f:
        f.setnchannels(2);f.setsampwidth(2);f.setframerate(SR)
        f.writeframes((a*32767).astype('<i2').tobytes())

def loudness(path):
    r=subprocess.run([FF,'-hide_banner','-i',str(path),'-af','loudnorm=I=-20:TP=-1.5:LRA=12:print_format=json','-f','null','-'],capture_output=True,check=True)
    s=r.stderr.decode('utf8','replace');j=json.loads(s[s.rfind('{'):s.rfind('}')+1])
    return {k:float(j[k]) for k in ['input_i','input_tp','input_lra']}
