// Part of the FairPlay SAP transcription (see FairPlaySap.kt): literal indices,
// rotations and constants are kept verbatim so the code stays comparable with the
// reference; naming every literal would only obscure that.
@file:Suppress("ktlint:standard:max-line-length", "MagicNumber")

package com.creolecast.app.airplay2

import com.creolecast.app.airplay2.FairPlaySapBytes.fairplayWordsBigEndian
import com.creolecast.app.airplay2.FairPlaySapBytes.fairplayWordsFromLittleEndian
import com.creolecast.app.airplay2.FairPlaySapBytes.readLittleEndian32
import com.creolecast.app.airplay2.FairPlaySapBytes.writeBigEndian32
import com.creolecast.app.airplay2.FairPlaySapBytes.writeLittleEndian32
import com.creolecast.app.airplay2.FairPlaySapBytes.writeLittleEndian64
import com.creolecast.app.airplay2.FairPlaySapHex.hexToBytes
import com.creolecast.app.airplay2.FairPlaySapHex.hexToInts
import com.creolecast.app.airplay2.FairPlaySapMd5.MUTATION_CYCLE
import com.creolecast.app.airplay2.FairPlaySapMd5.MUTATION_SWAP
import com.creolecast.app.airplay2.FairPlaySapMd5.fairplayMD5Compress

// fpsap_tables.go: the six white-box substitution bases, flattened to
// [table * 256 + value].
private val FPSAP_SUBSTITUTION_BASES = hexToInts(
    "491d7615f23ab6ac34a0697f81d788bb716e2816be7038ba9801a65b92ff208ed0b00ec92fde3d6f9f53b7fcc87b8264" +
        "feda5c0bf386b24236d6f5f8e92aef83cb9a8d12dde76558875900cafd8bf0844fb1bda1a585d19d547355f137c3d206" +
        "5626d85033f79ba41475cd3ba905676be6625f66bf03cfc15d4ac0aa45729e8a22948fb4890948174002fa60eeabb8e3" +
        "e13ec2c463e4748c46b90fd9f41c4447eb9141fb182da3a87a903f4dad436a937e116de29932d5b31edb31e85eec3996" +
        "51b5af9761a7c613682cf627e55a77ce0a233c309c190d2e0824f9104e7cd407c735d38095edbcae29e0dca26c2b2179" +
        "4cc52552cc571b4b0c1f1a04ea7ddf78576b2d8364328aedfe3f561adce631d0fc11aa35c53b91f821bf392ff36a33df" +
        "a3e91ba945bcc8d5501f0b90dbc9e0665dd39f6d41362514ec6f2bc1a47d42430e1e5f290cd87a5cae72b37b2aa8054b" +
        "ea9badb23d5ecf6cd6fb7552804ae25362a2b0e84dda04269982959e67ccef18938678f70379de96cd4cf5eb7eb7c7c0" +
        "8e098419a706e4bbf9cbfa71490f4ff6b5f13e7f5b94ca23f244a577d4ddb8befd0d92487481c63702162412a12c15b9" +
        "c234af54873c988f9d5aac1cabba7c5868bd467628408d08d78cc3132e8b17ff38ee654ee5d1f4b63059b401a0c47397" +
        "d91d27b19a106e60d261886389209cf0e70770e3473ace8522a60a00555169e1faf955718844caf7389801b758579948" +
        "9d8f26e983bc43dd1e0e543642d3eb9b35ec053c2fffb26cfb635fe5768e29d5b8cb652b141f18d8e01d61c97a399c4a" +
        "80db5bb4c5f212502d4d41cf172c282a2730246e22c20aaf3ea4fc40847f7252c85cfe7d03f5a83ac034c7df68a07bdc" +
        "568da15eee0f678c15643259a5f191eaa7e8fd82de60789f930870966d0c3d9089513be48779335dd7f8b06a23b61020" +
        "e64cf4b5f37ec16246edc63f37d4d6d0ce495a0dbbe7026b07b3c49ab99e75aebe9453a33147a6161bbd6f00adaa7773" +
        "1cd2b1d9f04f744eef66d195e285bf924b97a98aa2137c861921f6abe30611ccba8bcd25ace109040b81451a69da2ec3" +
        "ab0db5c89ded1150638b8379127c8d23a1eb4ce6d2537b62f01428ae9eafb68cc6b38540946d3258f7f886b1dddbfc37" +
        "5ba4ee46271a419389df035a598760d5e5b7c13cce1fc26f36095fe010524a614be742bea0de2da99121fe39b92668bb" +
        "25ec4f970a4efb81495c67c71c17b8a716adf969e43e00d9ac3d2e47d1741d54079bd35629dca6c9339c2afd72f50b7d" +
        "228096043fe92f34658438a32be124c47f57c0b4eaca45999075bc71b0f61bcb9f06ff9820d4fa9ad08a1870b25e133a" +
        "e3355d1ed8d6da51026608d7cff1302c6b95aaf2bac3ef780c8fa24d44437a7715a8c5f305f40e0f5548bfe2bd927e6c" +
        "cd82313bcc196e6a01767364e88ea588fa3b8755c613dc89b8c767c9bc822844afdf77d40549f67d07d9caf9e1082904" +
        "aed865ad63c223617f1f7e027c79cf1560f54ba758112a9d390c56a312bf699a6a7b2d2734cb99f43e800ed19078e6e4" +
        "76a06ef32b33413c3d4a837350a6421bb40b96184803e8475dcdaa8616bd62fbbb326ffee78f1dffb7a40d5fd752946c" +
        "71c8b2256decd6a535a1d2c046401c20be4feeb9f0eabad0383f8a2e0f218cb65c43d5f2684df7b157d3fcdac5fd8e66" +
        "abe317887292ac3a018dc18524f8f1ef599764ce8b759b5b510a3700dec3817a9eebe02c9c1e9fa906e2cc705e26b55a" +
        "09c4eddda21a9184b0224e2f109845e9e54c74959354db36a83031196bb353145467c51a5309a6ebac07c2e76ca78d7b" +
        "c1832a065121b73652e67414998e0f17554f48350ce33f2e4297821f840e4d7fe481c3bfa1a9af1c57d4287eb4186641" +
        "cc95457090e98fb3e5bd3e8c3cbba0fc3a9c65ca5b4e34044c2b1d30da3ba38af193e0c6777a5d926af476cf232c404b" +
        "b6de0d395620e25e44eafe259b62b931f2d9cb61ee1ed180a5c924d7e812ed3de1085c38bad364b526d2aa91be166b05" +
        "6dfbdf5f194a7589d6f89a2285d833d003688713b1dbc743019df9b8d5a4f6fa462f6e73c48b9471b2ec27110ac86fae" +
        "6996ce021015597dfddd0bb0efff2da87c9e9f98a288ad37abf050dc0086f7795a58f560c06329f3783272bc49471bcd"
)

// fpsap_tables.go: the seven white-box MixColumns bases.
private val FPSAP_MIX_BASES = hexToInts(
    "46ceb139ba324dc558d0af27a42c53db870f70f87bf38c0499116ee665ed921aea621d95169ee169f47c038b0880ff77" +
        "2ba3dc54d75f20a835bdc24ac9413eb6cf4738b033bbc44cd15926ae2da5da520e86f971f27a058d1098e76fec641b93" +
        "63eb941c9f1768e07df58a02810976fea22a55dd5ed6a921bc344bc340c8b73f3cb4cb43c04837bf22aad55dde5629a1" +
        "fd750a820189f67ee36b149c1f97e860901867ef6ce49b138e0679f172fa850d51d9a62ead255ad24fc7b830b33b44cc" +
        "b53d42ca49c1be36ab235cd457dfa02874fc830b88007ff76ae29d15961e61e91991ee66e56d129a078ff078fb730c84" +
        "d8502fa724acd35bc64e31b93ab2cd45fbfaf9f8fffefdfcf3f2f1f0f7f6f5f4ebeae9e8efeeedece3e2e1e0e7e6e5e4" +
        "dbdad9d8dfdedddcd3d2d1d0d7d6d5d4cbcac9c8cfcecdccc3c2c1c0c7c6c5c4bbbab9b8bfbebdbcb3b2b1b0b7b6b5b4" +
        "abaaa9a8afaeadaca3a2a1a0a7a6a5a49b9a99989f9e9d9c93929190979695948b8a89888f8e8d8c8382818087868584" +
        "7b7a79787f7e7d7c73727170777675746b6a69686f6e6d6c63626160676665645b5a59585f5e5d5c5352515057565554" +
        "4b4a49484f4e4d4c43424140474645443b3a39383f3e3d3c33323130373635342b2a29282f2e2d2c2322212027262524" +
        "1b1a19181f1e1d1c13121110171615140b0a09080f0e0d0c0302010007060504af265ad357dea22bb9304cc541c8b43d" +
        "7ef78b02860f73fa68e19d14901965ec23aad65fdb522ea735bcc049cd4438b1f27b078e0a83ff76e46d11981c95e960" +
        "66ef931a9e176be270f9850c88017df4b73e42cb4fc6ba33a12854dd59d0ac25ea631f96129be76efc750980048df178" +
        "3bb2ce47c34a36bf2da4d851d55c20a955dca029ad2458d143cab63fbb324ec7840d71f87cf58900921b67ee6ae39f16" +
        "d9502ca521a8d45dcf463ab337bec24b0881fd74f079058c1e97eb62e66f139a9c1569e064ed91188a037ff672fb870e" +
        "4dc4b831b53c40c95bd2ae27a32a56df1099e56ce8611d94068ff37afe770b82c14834bd39b0cc45d75e22ab2fa6da53" +
        "0134407587b2c6f30530447183b6c2f73e0b7f4ab88df9cc3a0f7b4ebc89fdc8083d497c8ebbcffa0c394d788abfcbfe" +
        "37027643b184f0c533067247b580f4c1d9ec98ad5f6a1e2bdde89ca95b6e1a2fe6d3a79260552114e2d7a39664512510" +
        "d0e591a456631722d4e195a052671326efdaae9b695c281debdeaa9f6d582c191c295d689aafdbee182d596c9eabdfea" +
        "23166257a590e4d127126653a194e0d51520546193a6d2e71124506597a2d6e32a1f6b5eac99edd82e1b6f5aa89de9dc" +
        "c4f185b042770336c0f581b446730732fbceba8f7d483c09ffcabe8b794c380dcdf88cb94b7e0a3fc9fc88bd4f7a0e3b" +
        "f2c7b38674413500f6c3b782704531046fb7c61e6db5c41ca97100d8ab7302dafa22538bf82051893ce4954d3ee6974f" +
        "de0677afdc0475ad18c0b1691ac2b36b4b93e23a4991e0388d5524fc8f5726fe924a3be3904839e1548cfd25568eff27" +
        "07dfae7605ddac74c11968b0c31b6ab223fb8a5221f98850e53d4c94e73f4e96b66e1fc7b46c1dc570a8d90172aadb03" +
        "855d2cf4875f2ef6439bea324199e83010c8b96112cabb63d60e7fa7d40c7da534ec9d4536ee9f47f22a5b83f0285981" +
        "a17908d0a37b0ad267bfce1665bdcc1478a0d1097aa2d30bbe6617cfbc6415cded35449cef37469e2bf3825a29f18058" +
        "c91160b8cb1362ba0fd7a67e0dd5a47c5c84f52d5e86f72f9a4233eb984031e9ba060eb2c47870cca8141ca0d66a62de" +
        "54e8e05c2a969e2246faf24e38848c303f838b3741fdf5492d91992553efe75bd16d65d9af131ba7c37f77cbbd0109b5" +
        "ab171fa3d56961ddb9050db1c77b73cf45f9f14d3b878f3357ebe35f29959d212e929a2650ece4583c80883442fef64a" +
        "c07c74c8be020ab6d26e66daac1018a45de1e955239f972b4ff3fb47318d8539b30f07bbcd7179c5a11d15a9df636bd7" +
        "d8646cd0a61a12aeca767ec2b40800bc368a823e48f4fc402498902c5ae6ee524cf0f844328e863a5ee2ea56209c9428" +
        "a21e16aadc6068d4b00c04b8ce727ac6c9757dc1b70b03bfdb676fd3a51911ad279b932f59e5ed513589813d4bf7ff43" +
        "7c2c227282d2dc8ca4f4faaa5a0a045428787626d68688d8f0a0aefe0e5e500061313f6f9fcfc191b9e9e7b747171949" +
        "35656b3bcb9b95c5edbdb3e313434d1d08585606f6a6a8f8d0808ede2e7e70205c0c0252a2f2fcac84d4da8a7a2a2474" +
        "15454b1bebbbb5e5cd9d93c333636d3d41111f4fbfefe1b199c9c79767373969ecbcb2e212424c1c34646a3aca9a94c4" +
        "b8e8e6b64616184860303e6e9ecec090f1a1afff0f5f510129797727d78789d9a5f5fbab5b0b05557d2d237383d3dd8d" +
        "98c8c6966636386840101e4ebeeee0b0cc9c92c232626c3c14444a1aeabab4e485d5db8b7b2b25755d0d0353a3f3fdad" +
        "d1818fdf2f7f712109595707f7a7a9f9"
)

/** One XOR-translated view of a base table: base[value xor inputXor] xor outputXor. */
private class FpsapByteLookup(
    private val table: Int,
    private val inputXor: Int,
    private val outputXor: Int
) {
    fun substitute(value: Int): Int =
        FPSAP_SUBSTITUTION_BASES[table * 256 + (value xor inputXor)] xor outputXor
    fun mix(value: Int): Int = FPSAP_MIX_BASES[table * 256 + (value xor inputXor)] xor outputXor
}

/** Decodes a run of (table, inputXor, outputXor) triples. */
private fun fpsapLookups(encoded: String): Array<FpsapByteLookup> {
    val raw = hexToInts(encoded)
    return Array(raw.size / 3) { FpsapByteLookup(raw[it * 3], raw[it * 3 + 1], raw[it * 3 + 2]) }
}

private fun chunkLookups(flat: Array<FpsapByteLookup>, width: Int): Array<Array<FpsapByteLookup>> =
    Array(flat.size / width) { row -> Array(width) { column -> flat[row * width + column] } }

private class FpsapNetworkTables(rounds: String, mix: String, final: String) {
    val roundSubstitution: Array<Array<FpsapByteLookup>> = chunkLookups(fpsapLookups(rounds), 16)
    val mixColumns: Array<Array<FpsapByteLookup>> = chunkLookups(fpsapLookups(mix), 4)
    val finalSubstitution: Array<FpsapByteLookup> = fpsapLookups(final)
}

private val FPSAP_FIRST_TABLES = FpsapNetworkTables(
    "00000000dedb002e930066b200b000000ddb00249300f8b200d20000fddb007e9300a1b2005b00009edb00199300f6b2" +
        "0100000163db0146930157b201a4000173db01859301cbb201c9000196db0136930105b201800001d7db01359301ffb2" +
        "0161000163db01069301f7b2012c0001a2db01fb930187b2010c000186db01b5930139b201650001e3db01f893017db2" +
        "012a000121db01a193015eb201ef000131db0122930162b2010a000105db01ef9301e0b20186000154db016f930126b2" +
        "01c100014adb01ed930145b201c70001c9db01b793019cb2012400017edb01209301c7b2014b000198db013793015ab2" +
        "01220001fddb0116930124b2010c000186db01d9930103b201c100014adb018193017fb20163000160db01ce93019eb2" +
        "0163000186db01d393011db201860001b2db01729301a5b201ae00014adb018b930161b20124000198db013d930144b2" +
        "01820001cfdb01929301b2b201ed0001cfdb01989301acb201aa000137db016b930176b2016700011ddb012e930189b2" +
        "01f4000114db018e9301e9b201f0000169db016e9301feb201b30001ecdb017d930133b2013d000143db012b930101b2",
    "0000000100000100d50200000200890000d001000901007901002102004800004801002b01004d0100390200040000e4",
    "02000002964802cd4202c656021924024d9302db260283a502439c02136202de02020ba402971d02e2d5028de502b18a"
)

private val FPSAP_SECOND_TABLES = FpsapNetworkTables(
    "030000035b380391d70352050300a4035b280391140352990300c9035bcd0391a7035257030080035b8c0391a40352ad" +
        "030061035b380391970352a503002c035bf903916a0352d503000c035bdd03912403526b030065035bb803916903522f" +
        "03002a035b7a03913003520c0300ef035b6a0391b303523003000a035b5e03917e0352b2030086035b0f0391fe035274" +
        "0300c1035b1103917c0352170300c7035b920391260352ce030024035b250391b103529503004b035bc30391a6035208" +
        "030022035ba603918703527603000c035bdd0391480352510300c1035b1103911003522d030063035b3b03915f0352cc" +
        "030063035bdd03914203524f030086035be90391e30352f70300ae035b1103911a035233030024035bc30391ac035216" +
        "030082035b940391030352e00300ed035b940391090352fe0300aa035b6c0391fa035224030067035b460391bf0352db" +
        "0300f4035b4f03911f0352bb0300f0035b320391ff0352ac0300b3035bb70391ec03526103003d035b180391ba035253" +
        "0400000448cd04425c04569404241904931604264a04a5d1049c4304624804024f04a459041d9704d5b904e51c048ae3",
    "0300000400000500000600000600010300830400d205007705006706001f03008c0400b80400ff05005e060056030004",
    "050000055bde05912e0552660500b0055b0d0591240552f80500d2055bfd05917e0552a105005b055b9e0591190552f6"
)

private val FPSAP_FIRST_INPUT_MASK = hexToInts("a09675fafe91ea7be48a79df6e71dd17")
private val FPSAP_SECOND_OUTPUT_MASK = hexToInts("67bc54c08e32851b50d2125f68b740a5")

private val FPSAP_FIRST_POSITION_MAP =
    intArrayOf(0, 5, 10, 15, 4, 9, 14, 3, 8, 13, 2, 7, 12, 1, 6, 11)
private val FPSAP_SECOND_POSITION_MAP =
    intArrayOf(0, 13, 10, 7, 4, 1, 14, 11, 8, 5, 2, 15, 12, 9, 6, 3)

// fpsap.go
private val FPSAP_DESCRIPTOR_PREFIX = hexToBytes("a0449c4d09e4bd7f6ec5d0cc359da7467a")
private val FPSAP_DESCRIPTOR_SUFFIX = hexToBytes("97b50f84e2155a9c24991cf43a09635547")
private val FPSAP_FIXED_BLOCK = hexToInts("afc22ba049effcfbfe67ac5ebef6fbcb")
private val FPSAP_MASK_SUFFIX = hexToBytes("57d8eecbdefbcf591c27a2cfbeb089")

/** fpsap.go: white-box networks and the m2/m3 exchange value. */
internal object FairPlaySapNetwork {
    // fairplay_crypto.go
    val FAIRPLAY_INITIAL_SESSION_KEY = hexToBytes("dcdcf3b90b74dcfb867ff76016729051")

    /** Derives the white-box seed from both halves of an exchange. */
    fun fpsapDescriptorForSAP(m3SAP: ByteArray, m2SAP: ByteArray): ByteArray {
        val padded = ByteArray(320)
        var offset = 0
        FPSAP_DESCRIPTOR_PREFIX.copyInto(padded, offset)
        offset += FPSAP_DESCRIPTOR_PREFIX.size
        m3SAP.copyInto(padded, offset)
        offset += m3SAP.size
        m2SAP.copyInto(padded, offset)
        offset += m2SAP.size
        FPSAP_DESCRIPTOR_SUFFIX.copyInto(padded, offset)
        offset += FPSAP_DESCRIPTOR_SUFFIX.size
        padded[offset] = 0x80.toByte()
        writeLittleEndian64(padded, padded.size - 8, offset.toLong() * 8)

        var state = fairplayWordsFromLittleEndian(FAIRPLAY_INITIAL_SESSION_KEY)
        var firstFinal = state
        var blockOffset = 0
        while (blockOffset < padded.size) {
            val add = fairplaySAPHash(padded, blockOffset)
            for (i in 0 until 4) state[i] = state[i] + readLittleEndian32(add, i * 4)
            state = fairplayMD5Compress(state, padded, blockOffset, MUTATION_CYCLE)
            if (blockOffset == padded.size - 64) {
                firstFinal = state
                state = fairplayMD5Compress(state, padded, blockOffset, MUTATION_CYCLE)
            }
            blockOffset += 64
        }

        val out = ByteArray(20)
        writeBigEndian32(out, 0, firstFinal[0])
        fairplayWordsBigEndian(state).copyInto(out, 4)
        return out
    }

    /** Nine 16-byte mask banks, flattened to [bank * 16 + byte]. */
    private fun fpsapMasks(seed: ByteArray): IntArray {
        val masks = IntArray(9 * 16)
        val state = intArrayOf(0x1d4a4587, 0x92f39fcc.toInt(), 0x1d87d836, 0xcdc86697.toInt())
        for (bank in 0 until 9) {
            val block = ByteArray(64)
            seed.copyInto(block, 0, 0, 20)
            block[20] = bank.toByte()
            FPSAP_MASK_SUFFIX.copyInto(block, 21)
            block[36] = 0x80.toByte()
            writeLittleEndian32(block, 56, 0x320)
            val digest = fairplayWordsBigEndian(fairplayMD5Compress(state, block, 0, MUTATION_SWAP))
            for (i in 0 until 16) masks[bank * 16 + i] = digest[i].toInt() and 0xff
        }
        return masks
    }

    private fun fpsapDigest32(left: IntArray, right: IntArray): IntArray {
        val block = ByteArray(64)
        for (i in 0 until 16) {
            block[i] = left[i].toByte()
            block[16 + i] = right[i].toByte()
        }
        block[32] = 0x80.toByte()
        writeLittleEndian32(block, 56, 0x100)
        val state = intArrayOf(0xb9f3dcdc.toInt(), 0xfbdc740b.toInt(), 0x60f77f86, 0x51907216)
        val digest = fairplayWordsBigEndian(fairplayMD5Compress(state, block, 0, MUTATION_SWAP))
        return IntArray(16) { digest[it].toInt() and 0xff }
    }

    private fun fpsapFirstNetwork(masks: IntArray): IntArray {
        val state = FPSAP_FIXED_BLOCK.copyOf()
        for (i in 0 until 16) state[i] = state[i] xor FPSAP_FIRST_INPUT_MASK[i]
        for (bank in 0 until 9) {
            val substituted = IntArray(16)
            for (output in 0 until 16) {
                val input = FPSAP_FIRST_POSITION_MAP[output]
                substituted[output] =
                    FPSAP_FIRST_TABLES.roundSubstitution[bank][input].substitute(state[input])
            }
            fpsapMix(FPSAP_FIRST_TABLES, state, substituted)
            for (i in 0 until 16) state[i] = state[i] xor masks[bank * 16 + i]
        }
        val out = IntArray(16)
        for (output in 0 until 16) {
            val input = FPSAP_FIRST_POSITION_MAP[output]
            out[output] = FPSAP_FIRST_TABLES.finalSubstitution[input].substitute(state[input])
        }
        return out
    }

    private fun fpsapSecondNetwork(input: IntArray, masks: IntArray): IntArray {
        val state = input.copyOf()
        for (bank in 8 downTo 0) {
            val substituted = IntArray(16)
            for (output in 0 until 16) {
                val source = FPSAP_SECOND_POSITION_MAP[output]
                substituted[output] =
                    FPSAP_SECOND_TABLES.roundSubstitution[bank][output].substitute(
                        state[source]
                    ) xor
                    masks[bank * 16 + output]
            }
            fpsapMix(FPSAP_SECOND_TABLES, state, substituted)
        }
        val out = IntArray(16)
        for (output in 0 until 16) {
            val source = FPSAP_SECOND_POSITION_MAP[output]
            out[output] =
                FPSAP_SECOND_TABLES.finalSubstitution[output].substitute(state[source]) xor
                FPSAP_SECOND_OUTPUT_MASK[output]
        }
        return out
    }

    private fun fpsapMix(tables: FpsapNetworkTables, state: IntArray, substituted: IntArray) {
        for (word in 0 until 4) {
            val offset = word * 4
            for (outputByte in 0 until 4) {
                var mixed = 0
                for (inputByte in 0 until 4) {
                    mixed =
                        mixed xor
                        tables.mixColumns[inputByte][outputByte].mix(
                            substituted[offset + inputByte]
                        )
                }
                state[offset + outputByte] = mixed
            }
        }
    }

    fun fpsapExchangeForSAP(m3SAP: ByteArray, m2SAP: ByteArray): ByteArray =
        fpsapExchangeSeed(fpsapDescriptorForSAP(m3SAP, m2SAP))

    private fun fpsapExchangeSeed(seed: ByteArray): ByteArray {
        val masks = fpsapMasks(seed)
        val intermediate = fpsapFirstNetwork(masks)
        val left = fpsapDigest32(intermediate, FPSAP_FIXED_BLOCK)
        val whiteboxOutput = fpsapSecondNetwork(left, masks)
        val digest = fpsapDigest32(left, whiteboxOutput)

        val out = ByteArray(20)
        for (i in 0 until 4) out[i] = whiteboxOutput[i].toByte()
        for (i in 0 until 16) out[4 + i] = digest[i].toByte()
        return out
    }
}
