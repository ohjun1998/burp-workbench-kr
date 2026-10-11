// Data-only extraction fixture. Opening this source does not run the suite.
export const API = '/api/diagnostics';
const path = id => API + '/fixed/objects/' + id;
async function call(url, options = {}) { return await fetch(url, options); }
call(path(objectId), {method:'GET', headers:{'X-Diag':'WB_STATIC_MARKER'}});
call(API+'/fixed/stored', {method:'POST', body:{value:'WB_STORE_DEMO01'}});
const config = {nested: user?.id ?? 0, ...options};
const fakeSecret = 'LAB_ONLY_PLACEHOLDER';
const regionHost = 'https://training-bucket.s3.amazonaws.com/fixture';
//# sourceMappingURL=/assets/diagnostics-modern.js.map
