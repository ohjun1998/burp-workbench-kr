package kr.workbench;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.mozilla.javascript.*;
import org.mozilla.javascript.ast.*;

/** Bounded static declarations only; never evaluates code or trusts escaping/mutated objects. */
final class JsBatchContext {
  private static final int MAX_DECLARATIONS=128, MAX_BYTES=65536, MAX_DECLARATION=8192;
  private enum Kind { SCALAR, OBJECT, FUNCTION }
  private record Declaration(String name,String text,int end,int namePosition,Kind kind,Set<String> leaves,Set<String> dependencies) {}
  private final LinkedHashMap<String,Declaration> declarations=new LinkedHashMap<>();
  private final Set<String> unsafe=new HashSet<>();
  private final long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
  private boolean limited;
  private int stored;

  JsBatchContext(String source) {
    collect(source);
    if(!limited)verifyUses(source);
    boolean changed;
    do {
      changed=false;
      for(var declaration:declarations.values())
        if(!unsafe.contains(declaration.name())&&declaration.dependencies().stream().anyMatch(unsafe::contains))
          changed|=unsafe.add(declaration.name());
    }while(changed);
  }
  String prelude(int before) {
    if(limited)return "";
    StringBuilder result=new StringBuilder();
    for(var declaration:declarations.values())if(declaration.end()<=before&&!unsafe.contains(declaration.name()))result.append(declaration.text()).append('\n');
    return result.toString();
  }
  String note() {
    long objects=declarations.values().stream().filter(d->d.kind()==Kind.OBJECT&&!unsafe.contains(d.name())).count();
    long functions=declarations.values().stream().filter(d->d.kind()==Kind.FUNCTION&&!unsafe.contains(d.name())).count();
    return limited?"문맥 사전 검사 제한 · 구간 간 연계 안 함":
      "구간 간 정적 상수·객체 "+objects+"개·단순 반환 함수 "+functions+"개 연계 가능 · 변경/별칭/동적 문맥 미확정";
  }
  private boolean budget(){if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)limited=true;return !limited;}

  private void collect(String source) {
    var cursor=new JsSyntax.Cursor(source);int braces=0,parens=0,squares=0,start=0,count=0;JsSyntax.Token first=null,token;
    while((token=cursor.next())!=null){
      if((++count&255)==0&&!budget())return;
      if(first==null){first=token;start=token.start();}
      if(token.quoted())continue;
      switch(token.text()){case "{"->braces++;case "}"->braces--;case "("->parens++;case ")"->parens--;case "["->squares++;case "]"->squares--;}
      if(braces<0||parens<0||squares<0){limited=true;return;}
      boolean boundary=braces==0&&parens==0&&squares==0&&(token.text().equals(";")||token.text().equals("}")&&first.text().equals("function"));
      if(boundary){
        if(token.end()-start<=MAX_DECLARATION&&Set.of("const","function","export").contains(first.text()))declaration(source.substring(start,token.end()),start,token.end());
        first=null;
      }
    }
    if(braces!=0||parens!=0||squares!=0)limited=true;
  }
  private void declaration(String text,int start,int end) {
    if(!budget())return;
    try {
      CompilerEnvirons env=new CompilerEnvirons();env.setLanguageVersion(Context.VERSION_ES6);env.setRecoverFromErrors(false);
      AstRoot root=new Parser(env).parse(JsSyntax.compatible(text).text(),"batch-context",1);
      if(root.getStatements().size()!=1)return;
      AstNode statement=root.getStatements().get(0),initializer;String name;int position;Kind kind;
      if(statement instanceof VariableDeclaration variables&&variables.isConst()&&variables.getVariables().size()==1&&variables.getVariables().get(0).getTarget() instanceof Name target){
        initializer=variables.getVariables().get(0).getInitializer();while(initializer instanceof ParenthesizedExpression parenthesized)initializer=parenthesized.getExpression();name=target.getIdentifier();position=start+target.getAbsolutePosition();
        kind=initializer instanceof FunctionNode?Kind.FUNCTION:initializer instanceof ObjectLiteral?Kind.OBJECT:Kind.SCALAR;
      }else if(statement instanceof FunctionNode function&&!function.getName().isEmpty()){
        initializer=function;name=function.getName();position=start+function.getFunctionName().getAbsolutePosition();kind=Kind.FUNCTION;
      }else return;
      if(declarations.containsKey(name)){unsafe.add(name);return;}
      Set<String> dependencies=new LinkedHashSet<>(),leaves=new HashSet<>();
      boolean valid=kind==Kind.FUNCTION?pureFunction((FunctionNode)initializer,dependencies):expression(initializer,Set.of(),dependencies,leaves,"",0);
      if(!valid||dependencies.contains(name))return;
      if(declarations.size()>=MAX_DECLARATIONS||stored+text.length()>=MAX_BYTES){limited=true;return;}
      declarations.put(name,new Declaration(name,text,end,position,kind,Set.copyOf(leaves),Set.copyOf(dependencies)));stored+=text.length();
    }catch(RuntimeException|StackOverflowError ignored){/* Unsupported declarations stay in their original unit. */}
  }
  private boolean pureFunction(FunctionNode function,Set<String> dependencies) {
    Set<String> parameters=new HashSet<>();
    for(var parameter:function.getParams())if(!(parameter instanceof Name name)||!parameters.add(name.getIdentifier()))return false;
    AstNode body=function.getBody(),returned=null;
    if(body instanceof Block block){
      if(block.getFirstChild()==null||block.getFirstChild()!=block.getLastChild()||!(block.getFirstChild() instanceof ReturnStatement statement))return false;
      returned=statement.getReturnValue();
    }else if(body instanceof ReturnStatement statement)returned=statement.getReturnValue();
    else if(function.isExpressionClosure())returned=body;
    return returned!=null&&!(returned instanceof ObjectLiteral)&&expression(returned,parameters,dependencies,new HashSet<>(),"",0);
  }
  private boolean expression(AstNode node,Set<String> parameters,Set<String> dependencies,Set<String> leaves,String path,int depth) {
    if(node==null||depth>8||!budget())return false;
    if(node instanceof ParenthesizedExpression p)return expression(p.getExpression(),parameters,dependencies,leaves,path,depth+1);
    if(node instanceof StringLiteral s){if(s.getValue().length()>MAX_DECLARATION)return false;leaves.add(path);return true;}
    if(node instanceof NumberLiteral||node instanceof KeywordLiteral){leaves.add(path);return true;}
    if(node instanceof Name name){
      Declaration declaration=declarations.get(name.getIdentifier());
      if(!parameters.contains(name.getIdentifier())){
        if(declaration==null||declaration.kind()!=Kind.SCALAR||unsafe.contains(name.getIdentifier()))return false;
        dependencies.add(name.getIdentifier());
      }
      leaves.add(path);return true;
    }
    if(node instanceof PropertyGet||node instanceof ElementGet){
      var reference=reference(node);if(reference==null)return false;Declaration declaration=declarations.get(reference.get(0));
      String property=String.join(".",reference.subList(1,reference.size()));
      if(declaration==null||declaration.kind()!=Kind.OBJECT||!declaration.leaves().contains(property)||unsafe.contains(declaration.name()))return false;
      dependencies.add(declaration.name());leaves.add(path);return true;
    }
    if(node instanceof InfixExpression infix&&infix.getOperator()==Token.ADD)
      return expression(infix.getLeft(),parameters,dependencies,leaves,path,depth+1)&&expression(infix.getRight(),parameters,dependencies,leaves,path,depth+1);
    if(node instanceof ObjectLiteral object){
      if(object.getElements().size()>64)return false;Set<String> keys=new HashSet<>();
      for(var item:object.getElements()){
        if(!(item instanceof ObjectProperty property)||property.isGetterMethod()||property.isSetterMethod()||property.isNormalMethod())return false;
        String key=property.getKey() instanceof Name n?n.getIdentifier():property.getKey() instanceof StringLiteral s?s.getValue():"";
        if(!key.matches("[A-Za-z_$][A-Za-z0-9_$]*")||!keys.add(key)||!expression(property.getValue(),parameters,dependencies,leaves,path.isEmpty()?key:path+"."+key,depth+1))return false;
      }
      return true;
    }
    if(node instanceof FunctionCall call&&call.getTarget() instanceof Name name){
      Declaration function=declarations.get(name.getIdentifier());
      if(function==null||function.kind()!=Kind.FUNCTION||unsafe.contains(function.name()))return false;
      dependencies.add(function.name());
      for(var argument:call.getArguments())if(!expression(argument,parameters,dependencies,leaves,path,depth+1))return false;
      leaves.add(path);return true;
    }
    return false;
  }
  private static List<String> reference(AstNode node){
    if(node instanceof Name name)return new ArrayList<>(List.of(name.getIdentifier()));
    AstNode target;String key;
    if(node instanceof PropertyGet property){target=property.getTarget();key=property.getProperty().getIdentifier();}
    else if(node instanceof ElementGet element&&element.getElement() instanceof StringLiteral literal){target=element.getTarget();key=literal.getValue();}
    else return null;
    List<String> result=reference(target);if(result!=null)result.add(key);return result;
  }
  private void verifyUses(String source){
    Lookahead tokens=new Lookahead(source);JsSyntax.Token older=null,previous=null,significant=null,olderSignificant=null,token;int count=0;Deque<Set<String>> targets=new ArrayDeque<>();
    while((token=tokens.next())!=null){
      if((++count&255)==0&&!budget())return;
      if(token.quoted()){
        // The streaming lexer hides template interpolation assignments; fail closed for referenced bindings.
        if(token.text().startsWith("`")&&token.text().contains("${"))for(String name:declarations.keySet())if(token.text().contains(name))unsafe.add(name);
        if(is(tokens.peek(0),"]")&&write(tokens,1))for(var d:declarations.values())
          if(d.kind()==Kind.FUNCTION&&(token.text().equals("'"+d.name()+"'")||token.text().equals("\""+d.name()+"\"")))unsafe.add(d.name());
        older=previous;previous=token;continue;
      }
      if(Set.of("eval","Function","with").contains(token.text())){limited=true;return;}
      if(Set.of("[","{").contains(token.text()))targets.push(new HashSet<>());
      if(Set.of("]","}").contains(token.text())&&!targets.isEmpty()){Set<String> names=targets.pop();if(write(tokens,0))unsafe.addAll(names);}
      Declaration declaration=declarations.get(token.text());
      if(declaration!=null&&token.start()!=declaration.namePosition()){
        for(Set<String> target:targets)target.add(declaration.name());
        if(is(significant,"delete")||significant!=null&&Set.of("+","-").contains(significant.text())&&is(olderSignificant,significant.text())||previous!=null&&Set.of("const","let","var","function").contains(previous.text()))unsafe.add(declaration.name());
        if(previous!=null&&Set.of("+","-").contains(previous.text())&&is(older,previous.text()))unsafe.add(declaration.name());
        if(previous!=null&&previous.text().equals(".")){
          if(declaration.kind()==Kind.FUNCTION&&write(tokens,0))unsafe.add(declaration.name());
        }else if(!is(tokens.peek(0),":")){
          if(declaration.kind()==Kind.SCALAR){
            if(write(tokens,0)||previous!=null&&Set.of("const","let","var","function").contains(previous.text()))unsafe.add(declaration.name());
          }else if(declaration.kind()==Kind.FUNCTION){if(!is(tokens.peek(0),"("))unsafe.add(declaration.name());}
          else {
            StringBuilder path=new StringBuilder();int look=0;
            while(look<32){
              JsSyntax.Token dot=tokens.peek(look),key=tokens.peek(look+1);String part;
              if(is(dot,".")&&key!=null&&!key.quoted()&&key.text().matches("[A-Za-z_$][A-Za-z0-9_$]*")){part=key.text();look+=2;}
              else if(is(dot,"[")&&key!=null&&key.quoted()&&key.text().matches("['\"][A-Za-z_$][A-Za-z0-9_$]*['\"]")&&is(tokens.peek(look+2),"]")){part=key.text().substring(1,key.text().length()-1);look+=3;}
              else break;
              if(!path.isEmpty())path.append('.');path.append(part);
            }
            boolean request=path.isEmpty()&&declaration.leaves().contains("url")&&is(tokens.peek(0),")")&&source.substring(Math.max(0,token.start()-80),token.start()).matches("(?s).*(?:^|[^A-Za-z0-9_$.])axios(?:\\s*\\.\\s*request)?\\s*\\(\\s*");
            if(!request&&(path.isEmpty()||!declaration.leaves().contains(path.toString())||write(tokens,look)||previous!=null&&previous.text().equals("delete")))unsafe.add(declaration.name());
          }
        }
      }
      older=previous;previous=token;if(!token.text().equals("(")){olderSignificant=significant;significant=token;}
    }
  }
  private static boolean write(Lookahead tokens,int index){
    int closing=0;while(is(tokens.peek(index),")")&&closing++<8)index++;
    JsSyntax.Token first=tokens.peek(index),second=tokens.peek(index+1);
    return is(first,"=")&&!is(second,"=")||is(first,"in")||is(first,"of")
      ||first!=null&&Set.of("+","-","*","/","%","&","|","^","??","&&","||").contains(first.text())&&(is(second,"=")||is(second,first.text()))
      ||first!=null&&Set.of("<",">").contains(first.text())&&is(second,first.text())&&(is(tokens.peek(index+2),"=")||is(tokens.peek(index+2),first.text())&&is(tokens.peek(index+3),"="));
  }
  private static boolean is(JsSyntax.Token token,String value){return token!=null&&token.text().equals(value);}
  private static final class Lookahead {
    private final JsSyntax.Cursor cursor;private final ArrayList<JsSyntax.Token> queued=new ArrayList<>();
    Lookahead(String source){cursor=new JsSyntax.Cursor(source);}
    JsSyntax.Token peek(int index){while(queued.size()<=index){var token=cursor.next();if(token==null)return null;queued.add(token);}return queued.get(index);}
    JsSyntax.Token next(){return queued.isEmpty()?cursor.next():queued.remove(0);}
  }
}
