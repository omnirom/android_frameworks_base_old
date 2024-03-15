/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content.pm;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.LargeTest;

import junit.framework.TestCase;

@Presubmit
@LargeTest
public class SignatureTest extends TestCase {

    /** Cert A with valid syntax */
    private static final Signature A = new Signature("308201D33082013CA0030201020219373565373461363A31336534333439623635343A2D38303030300D06092A864886F70D01010505003017311530130603550403130C6269736F6E416E64726F6964301E170D3133303432343232323134345A170D3338303432353232323134345A3017311530130603550403130C6269736F6E416E64726F696430819F300D06092A864886F70D010101050003818D00308189028181009214CE08563B77FF3128D3A303254287301263A842D19D5D4EAF024EBEDF864F3802C215B2F3EA85432F3EFF1DB8F591B0854FA7C1C6E4A8A85132FA762CC2D12A8EBD34D8B15C241A91716577F03BB3D2AFFC24367AB1E5E03C387891E34E646E47FAD75B178C1FD077B9199B3ABA6D48E2464801F6592E98245124046E51A90203010001A317301530130603551D25040C300A06082B06010505070303300D06092A864886F70D0101050500038181000B71581EDDC20E8C18C1C140BEE72501A97E04CA12030C51D4C38767B6A9FB5155CF4858C565EF77E5E2C22687C1AAB04BBA2B81C9A73CFB8DE118B624094AAE43D8FC2D585D90839DAFA5033AF7B8C0DE27E6ADAE44C40508CE493E9C80F1F5DA9EC87ECA1844BAB12C83CC8EB5937E1BE36A42CD22086A826E00FB763CD577");
    /** Cert A with malformed syntax */
    private static final Signature M = new Signature("308201D43082013CA0030201020219373565373461363A31336534333439623635343A2D38303030300D06092A864886F70D01010505003017311530130603550403130C6269736F6E416E64726F6964301E170D3133303432343232323134345A170D3338303432353232323134345A3017311530130603550403130C6269736F6E416E64726F696430819F300D06092A864886F70D010101050003818D00308189028181009214CE08563B77FF3128D3A303254287301263A842D19D5D4EAF024EBEDF864F3802C215B2F3EA85432F3EFF1DB8F591B0854FA7C1C6E4A8A85132FA762CC2D12A8EBD34D8B15C241A91716577F03BB3D2AFFC24367AB1E5E03C387891E34E646E47FAD75B178C1FD077B9199B3ABA6D48E2464801F6592E98245124046E51A90203010001A317301530130603551D25040C300A06082B06010505070303300D06092A864886F70D010105050003820081000B71581EDDC20E8C18C1C140BEE72501A97E04CA12030C51D4C38767B6A9FB5155CF4858C565EF77E5E2C22687C1AAB04BBA2B81C9A73CFB8DE118B624094AAE43D8FC2D585D90839DAFA5033AF7B8C0DE27E6ADAE44C40508CE493E9C80F1F5DA9EC87ECA1844BAB12C83CC8EB5937E1BE36A42CD22086A826E00FB763CD577");
    /** Cert B with valid syntax */
    private static final Signature B = new Signature("308204a830820390a003020102020900a1573d0f45bea193300d06092a864886f70d0101050500308194310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e20566965773110300e060355040a1307416e64726f69643110300e060355040b1307416e64726f69643110300e06035504031307416e64726f69643122302006092a864886f70d0109011613616e64726f696440616e64726f69642e636f6d301e170d3131303931393138343232355a170d3339303230343138343232355a308194310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e20566965773110300e060355040a1307416e64726f69643110300e060355040b1307416e64726f69643110300e06035504031307416e64726f69643122302006092a864886f70d0109011613616e64726f696440616e64726f69642e636f6d30820120300d06092a864886f70d01010105000382010d00308201080282010100de1b51336afc909d8bcca5920fcdc8940578ec5c253898930e985481cfdea75ba6fc54b1f7bb492a03d98db471ab4200103a8314e60ee25fef6c8b83bc1b2b45b084874cffef148fa2001bb25c672b6beba50b7ac026b546da762ea223829a22b80ef286131f059d2c9b4ca71d54e515a8a3fd6bf5f12a2493dfc2619b337b032a7cf8bbd34b833f2b93aeab3d325549a93272093943bb59dfc0197ae4861ff514e019b73f5cf10023ad1a032adb4b9bbaeb4debecb4941d6a02381f1165e1ac884c1fca9525c5854dce2ad8ec839b8ce78442c16367efc07778a337d3ca2cdf9792ac722b95d67c345f1c00976ec372f02bfcbef0262cc512a6845e71cfea0d020103a381fc3081f9301d0603551d0e0416041478a0fc4517fb70ff52210df33c8d32290a44b2bb3081c90603551d230481c13081be801478a0fc4517fb70ff52210df33c8d32290a44b2bba1819aa48197308194310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e20566965773110300e060355040a1307416e64726f69643110300e060355040b1307416e64726f69643110300e06035504031307416e64726f69643122302006092a864886f70d0109011613616e64726f696440616e64726f69642e636f6d820900a1573d0f45bea193300c0603551d13040530030101ff300d06092a864886f70d01010505000382010100977302dfbf668d7c61841c9c78d2563bcda1b199e95e6275a799939981416909722713531157f3cdcfea94eea7bb79ca3ca972bd8058a36ad1919291df42d7190678d4ea47a4b9552c9dfb260e6d0d9129b44615cd641c1080580e8a990dd768c6ab500c3b964e185874e4105109d94c5bd8c405deb3cf0f7960a563bfab58169a956372167a7e2674a04c4f80015d8f7869a7a4139aecbbdca2abc294144ee01e4109f0e47a518363cf6e9bf41f7560e94bdd4a5d085234796b05c7a1389adfd489feec2a107955129d7991daa49afb3d327dc0dc4fe959789372b093a89c8dbfa41554f771c18015a6cb242a17e04d19d55d3b4664eae12caf2a11cd2b836e");

    private boolean areExactMatch(Signature[] a, Signature[] b) throws Exception {
        SigningDetails ad1 = new SigningDetails(a,
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3);
        SigningDetails bd1 = new SigningDetails(b,
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3);
        return Signature.areExactMatch(ad1, bd1);
    }

    public void testExactlyEqual() throws Exception {
        assertTrue(areExactMatch(asArray(A), asArray(A)));
        assertTrue(areExactMatch(asArray(M), asArray(M)));

        assertFalse(areExactMatch(asArray(A), asArray(B)));
        assertFalse(areExactMatch(asArray(A), asArray(M)));
        assertFalse(areExactMatch(asArray(M), asArray(A)));

        assertTrue(areExactMatch(asArray(A, M), asArray(M, A)));
    }

    private boolean areEffectiveMatch(Signature[] a, Signature[] b) throws Exception {
        SigningDetails ad1 = new SigningDetails(a,
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3);
        SigningDetails bd1 = new SigningDetails(b,
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3);
        return Signature.areEffectiveMatch(ad1, bd1);
    }

    public void testEffectiveMatch() throws Exception {
        assertTrue(areEffectiveMatch(asArray(A), asArray(A)));
        assertTrue(areEffectiveMatch(asArray(M), asArray(M)));

        assertFalse(areEffectiveMatch(asArray(A), asArray(B)));
        assertTrue(areEffectiveMatch(asArray(A), asArray(M)));
        assertTrue(areEffectiveMatch(asArray(M), asArray(A)));

        assertTrue(areEffectiveMatch(asArray(A, M), asArray(M, A)));
        assertTrue(areEffectiveMatch(asArray(A, B), asArray(M, B)));
        assertFalse(areEffectiveMatch(asArray(A, M), asArray(A, B)));
    }

    public void testHashCode_doesNotIncludeFlags() throws Exception {
        // Some classes rely on the hash code not including the flags / capabilities for the signer
        // to verify Set membership. This test verifies two signers with the same signature but
        // different flags have the same hash code.
        Signature signatureAWithAllCaps = new Signature(A.toCharsString());
        // There are currently 5 capabilities that can be assigned to a previous signer, although
        // for the purposes of this test all that matters is that the two flag values are distinct.
        signatureAWithAllCaps.setFlags(31);
        Signature signatureAWithNoCaps = new Signature(A.toCharsString());
        signatureAWithNoCaps.setFlags(0);

        assertEquals(signatureAWithAllCaps.hashCode(), signatureAWithNoCaps.hashCode());
    }

    public void testEquals_doesNotIncludeFlags() throws Exception {
        // Similar to above some classes rely on equals only comparing the signature arrays
        // for equality without including the flags. This test verifies two signers with the
        // same signature but different flags are still considered equal.
        Signature signatureAWithAllCaps = new Signature(A.toCharsString());
        signatureAWithAllCaps.setFlags(31);
        Signature signatureAWithNoCaps = new Signature(A.toCharsString());
        signatureAWithNoCaps.setFlags(0);

        assertEquals(signatureAWithAllCaps, signatureAWithNoCaps);
    }

    private static Signature[] asArray(Signature... s) {
        return s;
    }
}
