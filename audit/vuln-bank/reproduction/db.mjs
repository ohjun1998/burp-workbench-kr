import {PGlite} from '@electric-sql/pglite';
import {PGLiteSocketServer} from '@electric-sql/pglite-socket';
const db=await PGlite.create();
const server=new PGLiteSocketServer({db,host:'127.0.0.1',port:24532,maxConnections:50,idleTimeout:0});
await server.start();
console.log('DB_READY',JSON.stringify(await db.query('select version()')));
process.on('SIGTERM',async()=>{await server.stop();await db.close();process.exit(0)});
