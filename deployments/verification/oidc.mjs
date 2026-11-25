import http from "node:http";
import { createHash, generateKeyPairSync, randomUUID, sign } from "node:crypto";

// Disposable test issuer on the private Compose network. Only the test Studio can redeem a code.
const issuer="http://oidc-fixture:9000";
const client="verification-studio";
const redirect="http://localhost:3000/";
const {publicKey,privateKey}=generateKeyPairSync("rsa",{modulusLength:2048});
const key={...publicKey.export({format:"jwk"}),kid:"verification",alg:"RS256",use:"sig"};
const codes=new Map();
function jwt(aud,nonce) {
 const now=Math.floor(Date.now()/1000);
 const claims={iss:issuer,sub:"browser-operator",aud,iat:now,exp:now+300,tenant_id:"oidc-verification",roles:["developer","operator"]};
 if(nonce)claims.nonce=nonce;
 const encode=value=>Buffer.from(JSON.stringify(value)).toString("base64url");
 const body=encode({alg:"RS256",kid:key.kid})+"."+encode(claims);
 return body+"."+sign("RSA-SHA256",Buffer.from(body),privateKey).toString("base64url");
}
http.createServer(async(req,res)=>{
 const url=new URL(req.url,issuer);
 res.setHeader("Access-Control-Allow-Origin","http://localhost:3000");
 res.setHeader("Cache-Control","no-store");
 function send(status,obj){res.writeHead(status,{"Content-Type":"application/json"});res.end(JSON.stringify(obj));}
 if(req.method==="OPTIONS"){res.writeHead(204,{"Access-Control-Allow-Methods":"POST, GET, OPTIONS","Access-Control-Allow-Headers":"content-type, authorization"});return res.end();}
 if(url.pathname==="/.well-known/openid-configuration")return send(200,{issuer,authorization_endpoint:issuer+"/authorize",token_endpoint:issuer+"/token",jwks_uri:issuer+"/jwks",id_token_signing_alg_values_supported:["RS256"],subject_types_supported:["public"],response_types_supported:["code"],code_challenge_methods_supported:["S256"]});
 if(url.pathname==="/jwks")return send(200,{keys:[key]});
 if(url.pathname==="/authorize"){
  const q=url.searchParams;
  if(q.get("client_id")!==client || q.get("redirect_uri")!==redirect || q.get("code_challenge_method")!=="S256" || !q.get("code_challenge"))return send(400,{error:"invalid_request"});
  const id=randomUUID();
  codes.set(id,{challenge:q.get("code_challenge"),nonce:q.get("nonce"),until:Date.now()+60000});
  const location=new URL(redirect);location.searchParams.set("code",id);location.searchParams.set("state",q.get("state"));
  res.writeHead(302,{Location:String(location)});return res.end();
 }
 if(url.pathname==="/token" && req.method==="POST"){
  let body="";for await(const chunk of req){body+=chunk;if(body.length>8192)return send(413,{});}
  const q=new URLSearchParams(body);const code=codes.get(q.get("code"));codes.delete(q.get("code"));
  if(!code || code.until<Date.now() || q.get("grant_type")!=="authorization_code" || q.get("client_id")!==client || q.get("redirect_uri")!==redirect || createHash("sha256").update(q.get("code_verifier")||"").digest("base64url")!==code.challenge)return send(400,{error:"invalid_grant"});
  return send(200,{access_token:jwt("agent-platform"),id_token:jwt(client,code.nonce),token_type:"Bearer",scope:"openid profile",expires_in:300});
 }
 send(404,{});
}).listen(9000,"0.0.0.0");
