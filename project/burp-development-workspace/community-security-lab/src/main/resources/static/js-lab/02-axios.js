// TRAINING ONLY: fake values; fetched as text, never executed.
axios.get('/api/js-lab/search',{params:{q:'training',page:1}});
axios.post('/api/js-lab/upload',{fileId:7,filename:'lab.txt'},{headers:{'X-Lab-Upload':'fixture'}});
axios.put('/api/js-lab/settings',{theme:'dark'});
axios.patch('/api/js-lab/preferences',{language:'ko'});
axios.delete('/api/js-lab/remove');
axios({baseURL:'/api/js-lab',url:'/audit',method:'POST',params:{limit:10},data:{event:'test'},headers:{'X-Lab-Audit':'fixture'}});
axios.request({url:'/api/js-lab/export',method:'HEAD'});
