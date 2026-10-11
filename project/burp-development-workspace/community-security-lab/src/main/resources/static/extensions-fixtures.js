// Synthetic training fixtures. Every key is fabricated and has no privileges.
const syntheticCloudKey = "AIza11111111111111111111111111111111111";
const syntheticAwsId = "AKIA1111111111111111";
const userRoute = "/api/vuln/orders/";
function loadTrainingOrder(id){return fetch(userRoute + id,{method:"GET",headers:{"X-Training":"true"}});}
function changeTrainingProfile(email){return fetch("/api/fixed/profile",{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body:"email="+email});}
const fixedRoute = "/api/fixed/orders/102";
//# sourceMappingURL=extensions-fixtures.js.map
