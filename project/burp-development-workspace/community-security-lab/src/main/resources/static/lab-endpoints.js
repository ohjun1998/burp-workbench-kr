// TRAINING FIXTURE: every key below is synthetic and unusable.
const labConfig = {
 apiKey: "lab_sk_demo_0123456789abcdef0123456789abcdef",
 accessKeyId: "AKIAIOSFODNN7EXAMPLE",
 token: "LAB_ONLY_DEMO_TOKEN_NOT_A_REAL_SECRET",
 adminUrl: "/api/vuln/admin/users?role=ADMIN",
 apiBase: "/api/vuln/",
 openApi: "/v3/api-docs"
};
function loadTrainingConfig() { return fetch("/api/vuln/config"); }
function loadTrainingOrder(orderId) { return fetch(`/api/vuln/orders/${orderId}`); }
function searchTrainingCatalog(query) { return fetch("/api/vuln/search?q=" + encodeURIComponent(query)); }
