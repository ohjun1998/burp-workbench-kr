import {PGlite} from '@electric-sql/pglite';
import {PGLiteSocketServer} from '@electric-sql/pglite-socket';
const port=Number(process.env.WB_BANK_DB_PORT || '24914');
if (!Number.isInteger(port) || port<1024 || port>65535) throw new Error('Invalid local DB port');
const db=await PGlite.create();
const server=new PGLiteSocketServer({db,host:'127.0.0.1',port,maxConnections:50,idleTimeout:0});
await server.start();
console.log('DB_READY',JSON.stringify(await db.query('select version()')));
process.on('SIGTERM',async()=>{await server.stop();await db.close();process.exit(0)});
