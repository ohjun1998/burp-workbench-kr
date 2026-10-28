package kr.workbench;

import burp.api.montoya.http.message.requests.HttpRequest;

/** Original occurrence identity is stronger than equal URL/body values. */
public final class OriginalRequestIdsTest extends WorkbenchTest {
  public static void main(String[] args) {
    OriginalRequestIds ids = new OriginalRequestIds();
    HttpRequest one = proxy(HttpRequest.class, (p,m,a) -> m.getName().equals("url") ? "https://identity.test/a" : def(m));
    HttpRequest two = proxy(HttpRequest.class, (p,m,a) -> m.getName().equals("url") ? "https://identity.test/a" : def(m));
    ids.remember(one,"store-a:1"); ids.remember(two,"store-a:2");
    check(ids.find(one).equals("store-a:1"), "first identical request keeps its exact captured occurrence");
    check(ids.find(two).equals("store-a:2"), "second identical request does not inherit the first original ID");
    HttpRequest untracked = proxy(HttpRequest.class,(p,m,a)->def(m));
    check(ids.find(untracked).isEmpty(), "untracked originals never fall back to a matching URL");
    ids.remember(one,"store-b:8");
    check(ids.find(one).equals("store-b:8"), "explicit new binding replaces only that object identity");
    ids.clear();check(ids.find(one).isEmpty() && ids.find(two).isEmpty(), "clear removes captured identity metadata");
    System.out.println("OriginalRequestIdsTest: 5 checks passed");
  }
}
