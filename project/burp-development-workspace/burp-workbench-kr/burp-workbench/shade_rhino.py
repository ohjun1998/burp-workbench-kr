"""Relocate Rhino constant-pool package names to avoid Burp/engine collisions."""
import struct
RENAMES=[(b'org/mozilla/javascript',b'kr/workbench/shaded/rhino'),(b'org.mozilla.javascript',b'kr.workbench.shaded.rhino'),(b'org/mozilla/classfile',b'kr/workbench/shaded/classfile'),(b'org.mozilla.classfile',b'kr.workbench.shaded.classfile')]
def rename(value):
 for old,new in RENAMES:value=value.replace(old,new)
 return value
def relocate(data):
 if not data.startswith(b'\xca\xfe\xba\xbe'):return data
 count=struct.unpack('>H',data[8:10])[0];result=bytearray(data[:10]);offset=10;i=1
 sizes={3:4,4:4,5:8,6:8,7:2,8:2,9:4,10:4,11:4,12:4,15:3,16:2,17:4,18:4,19:2,20:2}
 while i<count:
  tag=data[offset];offset+=1;result.append(tag)
  if tag==1:
   size=struct.unpack('>H',data[offset:offset+2])[0];offset+=2;value=rename(data[offset:offset+size]);offset+=size;result+=struct.pack('>H',len(value))+value
  else:
   size=sizes[tag];result+=data[offset:offset+size];offset+=size
   if tag in (5,6):i+=1
  i+=1
 result+=data[offset:];return bytes(result)
