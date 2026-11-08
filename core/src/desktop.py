# Fixed authenticated desktop integration. No client shell fragments.
import sys,json,subprocess,os,glob,shutil
LIMIT=262144
def run(args):
 p=subprocess.Popen(['timeout','4',*args],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
 try:
  data=p.stdout.read(LIMIT+1)
  if len(data)>LIMIT: raise RuntimeError('host metadata too large')
  if p.wait(timeout=3): raise RuntimeError('host command failed')
  return data
 finally:
  if p.poll() is None: p.kill()
  p.stdout.close()
def query(name): return json.loads(run(['hyprctl','-j',name]))
def short(v,n=256): return str(v)[:n]
# SSH login channels may lack the compositor environment. Resolve only this
# user's unique live instance; never guess between multiple desktops.
os.environ.setdefault('XDG_RUNTIME_DIR','/run/user/'+str(os.getuid()))
os.environ.setdefault('OMARCHY_PATH','/usr/share/omarchy')
instances=json.loads(run(['hyprctl','instances','-j']))
instances=[i for i in instances if os.stat('/proc/'+str(i['pid'])).st_uid==os.getuid()]
selected=[i for i in instances if i['instance']==os.environ.get('HYPRLAND_INSTANCE_SIGNATURE')]
if not selected and len(instances)==1: selected=instances
if len(selected)!=1: raise RuntimeError('ambiguous or missing compositor session')
os.environ['HYPRLAND_INSTANCE_SIGNATURE']=selected[0]['instance']
os.environ['WAYLAND_DISPLAY']=selected[0]['wl_socket']
kind=int(sys.argv[1]); identity=sys.argv[2] if len(sys.argv)>2 else ''
if kind==1:
 clients=query('clients'); outputs=query('monitors'); workspaces=query('workspaces')
 if len(clients)>128 or len(outputs)>32 or len(workspaces)>128: raise RuntimeError('too many desktop items')
 battery=None
 for path in glob.glob('/sys/class/power_supply/*')[:64]:
  try:
   def read(name):
    with open(os.path.join(path,name)) as f: return f.read(128).strip()
   if read('type')=='Battery' and (not os.path.exists(os.path.join(path,'scope')) or read('scope')!='Device'):
    battery={'percent':max(0,min(100,int(read('capacity')))),'status':short(read('status'),32)}; break
  except (OSError,ValueError): pass
 result={'clients':[{'address':short(c['address'],32),'class':short(c.get('class',''),128),'title':short(c.get('title',''),256),'workspace':int(c['workspace']['id']),'monitor':int(c['monitor'])} for c in clients if c.get('mapped') and not c.get('hidden')],
 'outputs':[{'name':short(m['name'],128),'width':int(m['width']),'height':int(m['height']),'scale':float(m['scale']),'focused':bool(m.get('focused'))} for m in outputs if not m.get('disabled')],
 'workspaces':[{'id':int(w['id']),'name':short(w['name'],128),'monitor':short(w['monitor'],128)} for w in workspaces], 'battery':battery,
 'capabilities':{'overview':False,'launcher':bool(shutil.which('omarchy-menu')),'volume':bool(shutil.which('omarchy-audio-output-volume')),'brightness':bool(shutil.which('omarchy-brightness-display')),'media':bool(shutil.which('omarchy-shell')),'text':bool(shutil.which('wtype'))}}
 encoded=json.dumps(result,separators=(',',':')).encode()
 if len(encoded)>65536: raise RuntimeError('projected metadata too large')
 sys.stdout.buffer.write(encoded)
elif kind==2:
 if identity not in [c['address'] for c in query('clients') if c.get('mapped')]: raise RuntimeError('window no longer exists')
 run(['hyprctl','dispatch','focuswindow','address:'+identity])
elif kind in (3,4): run(['hyprctl','dispatch','workspace','e-1' if kind==3 else 'e+1'])
elif kind==5:
 if int(identity) not in [w['id'] for w in query('workspaces')]: raise RuntimeError('workspace no longer exists')
 if int(identity)<1: raise RuntimeError('special workspace selection unsupported')
 run(['hyprctl','dispatch','workspace',identity])
elif kind in (6,7,8): run(['omarchy-audio-output-volume',{6:'raise',7:'lower',8:'mute-toggle'}[kind]])
elif kind in (9,10,11): run(['omarchy-shell','media',{9:'playPause',10:'next',11:'previous'}[kind]])
elif kind in (12,13): run(['omarchy-brightness-display','+5%' if kind==12 else '5%-'])
elif kind==14: run(['omarchy-menu','toggle'])
elif kind==15:
 text=sys.stdin.buffer.read(16385)
 if len(text)>16384 or b'\0' in text: raise RuntimeError('invalid text input')
 subprocess.run(['wtype','-'],input=text,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=5,check=True)
else: raise RuntimeError('unsupported action')
