const fs = require('fs');const {chromium} = require('playwright');
(async()=>{
 const browser=await chromium.launch({executablePath:process.argv[2],headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});
 const rows=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));const results=[];
 for(const item of rows){
  const cases=item.candidate?[item.rows.find(x=>x.verdict==='XSS 반사 후보')]:item.rows.filter(x=>x.payload.includes('console.log'));
  let executed=false,attempts=0;for(const row of cases){const page=await browser.newPage();const logs=[];page.on('console',msg=>logs.push(msg.text()));const marker=(row.payload.match(/PR_[0-9a-f]{16}/)||[])[0];await page.route('**/*',route=>route.abort());await page.setContent(row.body,{waitUntil:'load'});await page.waitForTimeout(90);executed ||= logs.includes(marker);attempts++;await page.close();}
  results.push({id:item.id,sourceTruth:item.sourceTruth,candidate:item.candidate,execution:executed,attempts,method:'Actual HTTP response body rendered in isolated Chrome 146; external requests blocked'});
 }
 fs.writeFileSync(process.argv[4],JSON.stringify(results,null,2));console.log('CHROME_CHECK',JSON.stringify({vulnerableExecution:results.filter(x=>x.sourceTruth&&x.execution).length,safeExecution:results.filter(x=>!x.sourceTruth&&x.execution).length,cases:results.length}));await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
