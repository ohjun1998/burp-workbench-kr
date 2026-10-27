package kr.workbench;

/** Reviewer-supplied identity metadata, never an assertion that the server verified identity. */
record AuthAccount(String name, String login, String userId, String role, String email) {
  AuthAccount {
    name=clean(name,64);login=clean(login,128);userId=clean(userId,128);
    role=clean(role,64);email=clean(email,256);
  }
  private static String clean(String value,int max) {
    String text=value==null?"":value.strip();
    if(text.length()>max||text.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("계정 정보 길이 또는 제어 문자를 확인하세요.");
    return text;
  }
  String label(){return name.isEmpty()?login.isEmpty()?userId.isEmpty()?"미매핑":userId:login:name+(login.isEmpty()||name.equals(login)?"":" · "+login);}
}
