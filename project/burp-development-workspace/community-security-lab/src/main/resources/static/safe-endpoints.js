// No credentials in the client bundle. Authorization is enforced by the server.
function loadConfig() { return fetch("/api/fixed/config", { credentials: "same-origin" }); }
function loadOrder(orderId) { return fetch(`/api/fixed/orders/${orderId}`); }
