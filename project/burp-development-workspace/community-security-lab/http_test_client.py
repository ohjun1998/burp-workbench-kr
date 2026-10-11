"""Small standard-library HTTP client used by tests.py (no pip install needed)."""
import urllib.request,urllib.parse,urllib.error,http.cookiejar,json
RequestException=(urllib.error.URLError, TimeoutError)
class Response:
 def __init__(self,r):
  self.status_code=r.code;self.headers=r.headers;self.content=r.read();self.text=self.content.decode('utf-8',errors='replace')
 def json(self):return json.loads(self.text)
class Session:
 def __init__(self):self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def request(self,method,url,params=None,data=None,headers=None,timeout=15):
  h=dict(headers or {})
  if params:url+='?'+urllib.parse.urlencode(params)
  if isinstance(data,dict):data=urllib.parse.urlencode(data).encode();h.setdefault('Content-Type','application/x-www-form-urlencoded')
  req=urllib.request.Request(url,data=data,headers=h,method=method)
  try:return Response(self.opener.open(req,timeout=timeout))
  except urllib.error.HTTPError as e:return Response(e)
 def get(self,url,**kw):return self.request('GET',url,**kw)
def get(url,**kw):return Session().get(url,**kw)
