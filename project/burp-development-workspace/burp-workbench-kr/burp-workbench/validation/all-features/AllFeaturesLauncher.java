package kr.workbench;
public final class AllFeaturesLauncher{public static void main(String[] args){
    TestLauncher.simulateUserConsent(); // Test-only explicit user-consent simulation for controlled HTTP adapters.
try{AllFeaturesTest.main(args);System.exit(0);}catch(Throwable e){e.printStackTrace();System.exit(1);}}}
