import { SignJWT, generateKeyPair, exportJWK, type KeyLike, type JWK } from 'jose';

export interface TestKeys {
  privateKey: KeyLike;
  publicJwk: JWK;
}

export async function generateTestKeys(): Promise<TestKeys> {
  const { privateKey, publicKey } = await generateKeyPair('RS256');
  const jwk = await exportJWK(publicKey);
  jwk.kid = 'test-key';
  jwk.alg = 'RS256';
  jwk.use = 'sig';
  return { privateKey, publicJwk: jwk };
}

export async function signTestToken(
  privateKey: KeyLike,
  opts: { sub: string; aud: string; iss: string; roles?: string[]; email?: string },
): Promise<string> {
  return new SignJWT({ roles: opts.roles ?? ['user'], email: opts.email })
    .setProtectedHeader({ alg: 'RS256', kid: 'test-key' })
    .setIssuer(opts.iss)
    .setAudience(opts.aud)
    .setSubject(opts.sub)
    .setIssuedAt()
    .setExpirationTime('5m')
    .sign(privateKey);
}
