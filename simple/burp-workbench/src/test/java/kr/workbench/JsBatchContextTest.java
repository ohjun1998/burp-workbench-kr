package kr.workbench;

import java.util.*;

/** Cross-unit static evidence and mutation/escape negative controls. No JS or network execution. */
public final class JsBatchContextTest {
  private static int checks;
  private static final String FILLER="\n/*"+"x".repeat(JsAnalysis.SOURCE_LIMIT)+"*/;\n";
  private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
  private static JsAnalysis.Result analyze(String source){return JsBatchAnalysis.analyze(source,"https://cdn.test/app.js","application/javascript","https://app.test/page");}
  private static JsAnalysis.Endpoint endpoint(JsAnalysis.Result result,String url,String method){return result.endpoints().stream().filter(e->e.url().equals(url)&&e.method().equals(method)).findFirst().orElseThrow(()->new AssertionError("missing "+method+" "+url+" in "+result));}
  private static boolean complete(JsAnalysis.Result result,String suffix){return result.endpoints().stream().anyMatch(e->e.complete()&&e.url().endsWith(suffix)&&!e.method().equals("?"));}
  public static void main(String[] args){
    String prefix="const apiRoot='https://api.test/v2';const config={baseURL:apiRoot,method:'POST'};";
    String call="axios.request({baseURL:config.baseURL,url:'/orders',method:config.method});";
    String source=prefix+FILLER+call;
    var result=analyze(source);var item=endpoint(result,"https://api.test/v2/orders","POST");
    check(item.complete(),"read-only object config links a baseURL and method across units");
    check(item.start()==source.lastIndexOf("'/orders'")&&item.line()==3,"cross-unit prelude keeps exact original offset and line");
    check(result.requestDrafts().get(JsAnalysis.draftKey(item)).url().equals(item.url()),"draft uses the recovered API origin instead of the document origin");
    check(result.truncated()&&result.status().contains("미확정"),"bounded semantic support never claims complete bundle recovery");
    check(result.status().contains("객체 1개"),"status identifies statically usable object context");

    for(String target:List.of("axios","axios.request")){
      String objectCall="const request={baseURL:'https://api.test',url:'/orders',method:'POST'};"+FILLER+target+"(request);";
      var request=analyze(objectCall);var requestEndpoint=endpoint(request,"https://api.test/orders","POST");
      check(requestEndpoint.complete()&&requestEndpoint.start()==objectCall.lastIndexOf("request)")&&requestEndpoint.line()==3,"static whole request object links at actual call argument: "+target);
      check(request.requestDrafts().get(JsAnalysis.draftKey(requestEndpoint)).method().equals("POST"),"whole object draft preserves POST: "+target);
      check(!complete(analyze(objectCall.replace(target+"(request);","request.url='/changed';"+target+"(request);")),"/orders"),"mutated whole request config is not carried: "+target);
    }
    String nested="const config={api:{baseURL:'https://api.test/v3',verb:'PATCH'}};"+FILLER+"fetch(config['api'].baseURL+'/item',{method:config.api['verb']});";
    check(endpoint(analyze(nested),"https://api.test/v3/item","PATCH").complete(),"nested data properties and constant bracket keys resolve");
    String function=prefix+"function route(id){return config.baseURL+'/users/'+id;}"+FILLER+"fetch(route('42'),{method:config.method});";
    var fn=endpoint(analyze(function),"https://api.test/v2/users/42","POST");
    check(fn.complete()&&fn.start()==function.lastIndexOf("route('42')"),"simple return function links dependencies and preserves invocation evidence");
    String arrow=prefix+"const route=(id)=>config.baseURL+'/users/'+id;"+FILLER+"fetch(route('43'));";
    check(endpoint(analyze(arrow),"https://api.test/v2/users/43","GET").complete(),"pure arrow return is linked without evaluating code");
    String dynamic=prefix+"function route(id){return config.baseURL+'/users/'+id;}"+FILLER+"fetch(route(currentId));";
    check(!complete(analyze(dynamic),"/users/{dynamic}"),"unknown function argument cannot become a complete path");

    for(String mutation:List.of("config.baseURL='https://other.test';","config['baseURL']='/other';","config.baseURL += '/other';","delete config.baseURL;","++config.baseURL;","config.baseURL <<= 1;","(config.baseURL)='/other';","[config.baseURL]=['/other'];","({x:config.baseURL}={x:'/other'});","delete (config.baseURL);","++(config.baseURL);","const alias=config;","mutate(config);","Object.assign(config,{baseURL:'/other'});","const t=`${config.baseURL='/other'}`;")){
      String changed=prefix+FILLER+mutation+"fetch(config.baseURL+'/orders');";
      check(!complete(analyze(changed),"/v2/orders"),"mutated or escaped config is not carried: "+mutation);
    }
    var unknownBase=analyze(prefix+FILLER+"mutate(config);axios.request({baseURL:config.baseURL,url:'/orders',method:'POST'});");
    check(unknownBase.endpoints().stream().anyMatch(e->e.url().equals("/orders")&&!e.complete()&&e.evidence().contains("기본 경로 미확정")),"unresolved baseURL preserves a relative candidate rather than inventing the page host");
    check(unknownBase.endpoints().stream().noneMatch(e->e.url().equals("https://app.test/orders")),"unknown API origin has no duplicate page-host config candidate");
    String parenthesized="const config=({root:'/old'});"+FILLER+"config.root='/new';fetch(config.root+'/orders');";
    check(!complete(analyze(parenthesized),"/old/orders"),"parenthesized object initializer retains object mutation checks");
    for(String mutation:List.of("config.api.root='/new';","[config.api.root]=['/new'];","({x:config.api.root}={x:'/new'});","delete (config.api.root);","++(config.api.root);","const alias=config.api;alias.root='/new';","mutate(config.api);")){
      String local="const config={api:{root:'/old'}};"+mutation+"fetch(config.api.root+'/orders');"+FILLER;
      check(!complete(analyze(local),"/old/orders"),"same-unit nested mutation/escape cannot become a static path: "+mutation);
    }
    for(String dynamicCode:List.of("(0,eval)(\"config.root='/new'\");","Function(\"config.root='/new'\")();","const evaluator=eval;evaluator(\"config.root='/new'\");")){
      String script="const config={root:'/old'};"+FILLER+dynamicCode+"fetch(config.root+'/orders');";
      check(!complete(analyze(script),"/old/orders"),"indirect dynamic execution disables cross-unit trust: "+dynamicCode);
      check(!complete(analyze("const config={api:{root:'/old'}};"+dynamicCode.replace("config.root","config.api.root")+"fetch(config.api.root+'/orders');"+FILLER),"/old/orders"),"dynamic execution disables same-unit binding trust");
    }
    String nestedEscape="const config={api:{root:'/original'}};const alias=config.api;"+FILLER+"alias.root='/changed';fetch(config.api.root+'/orders');";
    check(!complete(analyze(nestedEscape),"/original/orders"),"escaping nested object invalidates its parent context");
    String dependency=prefix+"function route(id){return config.baseURL+'/users/'+id;}"+FILLER+"mutate(config);fetch(route('42'));";
    check(!complete(analyze(dependency),"/v2/users/42"),"unsafe object invalidation propagates to dependent functions");
    String reassigned="function route(id){return '/old/'+id;}"+FILLER+"route=other;fetch(route('42'));";
    check(!complete(analyze(reassigned),"/old/42"),"reassigned function cannot be reused from a prior unit");
    String branch="function route(id){if(enabled)return '/conditional/'+id;}"+FILLER+"fetch(route('42'));";
    check(!complete(analyze(branch),"/conditional/42"),"conditional single return is not treated as an unconditional pure helper");
    check(!complete(analyze(branch.replace(FILLER,";")+FILLER),"/conditional/42"),"same-unit conditional return is also unresolved");
    String effect="function route(id){sideEffect();return '/effect/'+id;}"+FILLER+"fetch(route('42'));";
    check(!complete(analyze(effect),"/effect/42"),"effectful function body is not transplanted");
    check(!complete(analyze(effect.replace(FILLER,";")+FILLER),"/effect/42"),"same-unit effectful return is also unresolved");
    String getters="const config={get root(){return '/getter';}};"+FILLER+"fetch(config.root+'/orders');";
    check(!complete(analyze(getters),"/getter/orders"),"getter properties are never invoked or treated as data");
    String later="fetch(config.root+'/early');"+FILLER+"const config={root:'/late'};";
    check(!complete(analyze(later),"/late/early"),"later declarations cannot be promoted before initialization");
    String unsafeTemplate=prefix+FILLER+"eval('config.baseURL=other');fetch(config.baseURL+'/orders');";
    check(!complete(analyze(unsafeTemplate),"/v2/orders"),"dynamic eval disables context trust");
    String huge="const config={root:'/old'};function huge(){const text='"+"x".repeat(4*1024*1024)+"';fetch(config.root+'/orders');}";
    check(!complete(analyze(huge),"/old/orders"),"forced lexical split cannot trust carried context");
    check(analyze(huge).truncated(),"forced split remains visibly partial");
    System.out.println("JsBatchContextTest: "+checks+" checks passed");
  }
}
