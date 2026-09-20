package com.creolecast.app.airplay2

import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom

// Sender side of the FairPlay SAP (/fp-setup) handshake.
//
// Receivers that advertise feature bit 14 (FeatureFPSAP25) reject SETUP with
// RTSP 455 until this two-phase exchange has completed. The transform is a
// line-for-line transcription of the doubletake Go reference
// (internal/airplay/{fairplay,fpsap,fpsap_tables,fairplay_message,fairplay_md5,
// fairplay_crypto,fairplay_sap}.go), which is GPL-3 like this project.
//
// The HTTP transport is the caller's responsibility: POST message1() to
// /fp-setup with "X-Apple-ET: 32", feed the reply to exchangeM3(), POST that,
// feed the reply to finish(), then wrapKey() the stream key.
//
// Every byte value is carried in an Int masked to 0..255 so that Go's unsigned
// byte arithmetic (wrapping add/sub/multiply, unsigned divide and shift)
// transcribes directly; Kotlin's signed Byte would silently differ.

/** Result of a completed FairPlay SAP handshake. */
class FairPlayKeys(val ekey: ByteArray, val eiv: ByteArray)

// ---------------------------------------------------------------------------
// Constant tables. Encoded as hex and decoded once, because the expanded
// literals would blow past the JVM 64 KB method limit.
// ---------------------------------------------------------------------------

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> throw IllegalArgumentException("invalid hex digit '$c'")
}

private fun hexToInts(hex: String): IntArray =
    IntArray(hex.length / 2) { (hexDigit(hex[it * 2]) shl 4) or hexDigit(hex[it * 2 + 1]) }

private fun hexToBytes(hex: String): ByteArray {
    val values = hexToInts(hex)
    return ByteArray(values.size) { values[it].toByte() }
}

private fun hexToWordsBigEndian(hex: String): IntArray {
    val values = hexToInts(hex)
    return IntArray(values.size / 4) {
        (values[it * 4] shl 24) or (values[it * 4 + 1] shl 16) or
            (values[it * 4 + 2] shl 8) or values[it * 4 + 3]
    }
}

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
private class FpsapByteLookup(private val table: Int, private val inputXor: Int, private val outputXor: Int) {
    fun substitute(value: Int): Int = FPSAP_SUBSTITUTION_BASES[table * 256 + (value xor inputXor)] xor outputXor
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

private val FPSAP_FIRST_POSITION_MAP = intArrayOf(0, 5, 10, 15, 4, 9, 14, 3, 8, 13, 2, 7, 12, 1, 6, 11)
private val FPSAP_SECOND_POSITION_MAP = intArrayOf(0, 13, 10, 7, 4, 1, 14, 11, 8, 5, 2, 15, 12, 9, 6, 3)

// fpsap.go
private val FPSAP_M1_PAYLOAD = hexToBytes("020003bb")
private val FPSAP_M3_LABEL = hexToBytes("8f1a9c")
private val FPSAP_DESCRIPTOR_PREFIX = hexToBytes("a0449c4d09e4bd7f6ec5d0cc359da7467a")
private val FPSAP_DESCRIPTOR_SUFFIX = hexToBytes("97b50f84e2155a9c24991cf43a09635547")
private val FPSAP_FIXED_BLOCK = hexToInts("afc22ba049effcfbfe67ac5ebef6fbcb")
private val FPSAP_MASK_SUFFIX = hexToBytes("57d8eecbdefbcf591c27a2cfbeb089")

// fairplay_crypto.go
private val FAIRPLAY_INITIAL_SESSION_KEY = hexToBytes("dcdcf3b90b74dcfb867ff76016729051")
private val FAIRPLAY_KDF_PREFIX = hexToBytes("fa9cad4d4b68268c7ff38899de922e951e")
private val FAIRPLAY_KDF_SUFFIX = hexToBytes("ec4e275efdf2e83097ae70fbe0003f1c39")
private val FAIRPLAY_EKEY_HEADER = hexToBytes("46504c5901020100000000 3c00000000".replace(" ", ""))

// fairplay_message.go
private val FAIRPLAY_MESSAGE_IV = hexToInts(
    "5752f1b7549d8f870c10485a6088cadbdf7b1563f005587752a90402b9a3929568b54611fb04de676c968efb8c9db0c9" +
        "27078b2123361e7adc9d0b115354690d"
)

/** Mode 0..3; the receiver selects one in m2. */
private const val FAIRPLAY_MODE_COUNT = 4

private val FAIRPLAY_MESSAGE_ROUND_KEY0 = hexToInts("d30dfdb563def72b012ead72884dca25")
private val FAIRPLAY_MESSAGE_ROUND_KEY10 = hexToInts("cd5ecf47e9af289b51188f68d085eb69")

// [(mode * 9 + round) * 16 + byte]
private val FAIRPLAY_MESSAGE_MIDDLE_KEYS = hexToInts(
    "6af008a6ae5118268acccb4357d1e711c6b579e0dfce8114d41d91c868513be3bafc24065ebc9cf6131652de75afd0de" +
        "81a8b516836ee6493cccc125db99e0ec781b6ceb300cfe8f3c0c54c92dc7e2a3594cc871ad4a3dd851a884b0ed048f5c" +
        "afea245eae1f2de557bb511b17ddcc3d8e7a945569c09856533872ca6a3a00def3ca31810cb6201b24b5c7e4ee108bc0" +
        "1b9018bea49613e86ab413627c84a766c94c1dc55c8bbb04a940f640f420eba5d5664239df63981372b7a8b2360b160b" +
        "462cd7af87b8005a47157e32644557cff0c0ac26f01988a8573d39e298f97f6a9407abf947788ceada25ee558333edb6" +
        "3f820f9ed823ce2f7722c4bab1d2e33571fca0ff354f8991109961878bfa7cfb566c98381eedcfe7954f7213f61fc87e" +
        "2ab2ebe11496a9655dd1b28a9910ee309cbc01d64fa41ca1f705a0ef7fb4793408f02908920c5b348d32d3470b07705b" +
        "8735404470486fa4a42b04fbfde11e3cd8eba4780f69653dfc7d2b4fbfb1ac68a74a7a6696be42e1b884e1acff1311a4" +
        "cc7a007cd8a3f330d3f17ba3437d52cd1edeff958a6254b20114cfddc02a48f83a36f4e069e1907b2c8c779be7336c09" +
        "f6a223b4f01d8a7927a7c965de554de00cd1515d43246c87fc2f494364a4cfbcd66e3bdd6c7f1b811f90291984b5eaee" +
        "530d7ae85b3040569f13130f18fbb1e50784c47a9fe10f3d5ea5432ecdd9f585b9b0368eb7d134d90ebd1b94b255a4fb" +
        "b2a4352534066fdec1ce38db3d8c3b850b317a942520622d89578e9fe5a13fc3bdf569e6befc1d2fc08baf733f1d176a"
)

private val INVERSE_AES_SBOX = hexToInts(
    "52096ad53036a538bf40a39e81f3d7fb7ce339829b2fff87348e4344c4dee9cb547b9432a6c2233dee4c950b42fac34e" +
        "082ea16628d924b2765ba2496d8bd12572f8f66486689816d4a45ccc5d65b6926c704850fdedb9da5e154657a78d9d84" +
        "90d8ab008cbcd30af7e45805b8b34506d02c1e8fca3f0f02c1afbd0301138a6b3a9111414f67dcea97f2cfcef0b4e673" +
        "96ac7422e7ad3585e2f937e81c75df6e47f11a711d29c5896fb7620eaa18be1bfc563e4bc6d279209adbc0fe78cd5af4" +
        "1fdda8338807c731b11210592780ec5f60517fa919b54a0d2de57a9f93c99cefa0e03b4dae2af5b0c8ebbb3c83539961" +
        "172b047eba77d626e169146355210c7d"
)

private val FORWARD_AES_SBOX = IntArray(256).also { table ->
    for (encrypted in 0 until 256) table[INVERSE_AES_SBOX[encrypted]] = encrypted
}

// fairplay_md5.go
private const val MUTATION_SWAP = 0
private const val MUTATION_CYCLE = 1
private const val MUTATION_KDF = 2

private val FAIRPLAY_MD5_SHIFT = intArrayOf(
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
)

private val FAIRPLAY_MD5_CONSTANT = hexToWordsBigEndian(
    "d76aa478e8c7b756242070dbc1bdceeef57c0faf4787c62aa8304613fd469501698098d88b44f7afffff5bb1895cd7be" +
        "6b901122fd987193a679438e49b40821f61e2562c040b340265e5a51e9b6c7aad62f105d02441453d8a1e681e7d3fbc8" +
        "21e1cde6c33707d6f4d50d87455a14eda9e3e905fcefa3f8676f02d98d2a4c8afffa39428771f6816d9d6122fde5380c" +
        "a4beea444bdecfa9f6bb4b60bebfbc70289b7ec6eaa127fad4ef308504881d05d9d4d039e6db99e51fa27cf8c4ac5665" +
        "f4292244432aff97ab9423a7fc93a039655b59c38f0ccc92ffeff47d85845dd16fa87e4ffe2ce6e0a30143144e0811a1" +
        "f7537e82bd3af2352ad7d2bbeb86d391"
)

// fairplay_sap.go
private val SAP_HASH_INIT = hexToInts("965fc653f846cc18dfbeb2f83862ec2293d1208f")
private val SAP_MATRIX_INIT = hexToInts("4354627a18c3d6b39a56f61c143f0c1d3b3683b139514aaa093efe44afdec3209d42b8")
private val SAP_SEED = hexToInts("ed25d1bbbc279f02a2a911000cb352c0bde31b49c7")

// ---------------------------------------------------------------------------
// Byte and word helpers. Values are Ints holding 0..255 unless stated.
// ---------------------------------------------------------------------------

private fun rotl8(value: Int, count: Int): Int {
    val shift = count and 7
    return ((value shl shift) or (value ushr (8 - shift))) and 0xff
}

private fun rotateOrZero(input: Int, count: Int): Int {
    val amount = count and 0xff
    return if (amount == 0) 0 else rotl8(input, amount)
}

private fun wideSeed(input: Int, count: Int): Int {
    if ((count and 0xff) == 0) return SAP_SEED[0]
    return SAP_SEED[((input shl count) or (input ushr (8 - count))) % 21]
}

private fun majority(a: Int, b: Int, c: Int): Int = a xor ((a xor b) and (a xor c))

private fun selectBits(mask: Int, ifSet: Int, ifClear: Int): Int = ifClear xor ((ifSet xor ifClear) and mask)

private fun square(value: Int): Int = (value * value) and 0xff

private fun cube(value: Int): Int = (value * value * value) and 0xff

private fun not8(value: Int): Int = value.inv() and 0xff

private fun readBigEndian32(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or (source[offset + 3].toInt() and 0xff)

private fun writeBigEndian32(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value ushr 24).toByte()
    target[offset + 1] = (value ushr 16).toByte()
    target[offset + 2] = (value ushr 8).toByte()
    target[offset + 3] = value.toByte()
}

private fun readLittleEndian32(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or ((source[offset + 3].toInt() and 0xff) shl 24)

private fun writeLittleEndian32(target: ByteArray, offset: Int, value: Int) {
    for (i in 0 until 4) target[offset + i] = (value ushr (8 * i)).toByte()
}

private fun writeLittleEndian64(target: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) target[offset + i] = (value ushr (8 * i)).toByte()
}

private fun fairplayWordsFromLittleEndian(input: ByteArray): IntArray =
    IntArray(4) { readLittleEndian32(input, it * 4) }

private fun fairplayWordsBigEndian(words: IntArray): ByteArray {
    val out = ByteArray(16)
    for (i in 0 until 4) writeBigEndian32(out, i * 4, words[i])
    return out
}

// ---------------------------------------------------------------------------
// fairplay_md5.go: standard MD5 rounds, big-endian message words and a
// message-schedule mutation after round 31.
// ---------------------------------------------------------------------------

private fun fairplayMD5Compress(state: IntArray, block: ByteArray, blockOffset: Int, mutation: Int): IntArray {
    val message = IntArray(16) { readBigEndian32(block, blockOffset + it * 4) }

    var a = state[0]
    var b = state[1]
    var c = state[2]
    var d = state[3]
    for (round in 0 until 64) {
        val f: Int
        val word: Int
        if (round < 16) {
            f = (b and c) or (b.inv() and d)
            word = round
        } else if (round < 32) {
            f = (d and b) or (d.inv() and c)
            word = (5 * round + 1) and 15
        } else if (round < 48) {
            f = b xor c xor d
            word = (3 * round + 5) and 15
        } else {
            f = c xor (b or d.inv())
            word = (7 * round) and 15
        }

        val mixed = b + Integer.rotateLeft(a + f + FAIRPLAY_MD5_CONSTANT[round] + message[word], FAIRPLAY_MD5_SHIFT[round])
        a = d
        d = c
        c = b
        b = mixed

        if (round == 31) mutateFairplayMD5Message(message, a, b, c, d, mutation)
    }

    return intArrayOf(state[0] + a, state[1] + b, state[2] + c, state[3] + d)
}

private fun mutateFairplayMD5Message(message: IntArray, a: Int, b: Int, c: Int, d: Int, mutation: Int) {
    fun swap(i: Int, j: Int) {
        val held = message[i]
        message[i] = message[j]
        message[j] = held
    }
    when (mutation) {
        MUTATION_SWAP, MUTATION_CYCLE -> {
            val indices = intArrayOf(
                a and 15, b and 15, c and 15, d and 15,
                (a ushr 4) and 15, (b ushr 4) and 15, (c ushr 4) and 15, (d ushr 4) and 15,
            )
            if (mutation == MUTATION_SWAP) {
                for (i in indices.indices) swap(i, indices[i])
            } else {
                val first = message[indices[0]]
                for (i in 0 until indices.size - 1) message[indices[i]] = message[indices[i + 1]]
                message[indices[indices.size - 1]] = first
            }
        }
        else -> {
            swap(a and 15, b and 15)
            swap(c and 15, d and 15)
            var shift = 4
            while (shift <= 12) {
                swap((a ushr shift) and 15, (b ushr shift) and 15)
                shift += 4
            }
        }
    }
}

// ---------------------------------------------------------------------------
// fairplay_sap.go: FairPlay's proprietary SAP hash. Not a standard hash.
// ---------------------------------------------------------------------------

private fun fairplaySAPHash(block: ByteArray, blockOffset: Int): ByteArray {
    val hash = SAP_HASH_INIT.copyOf()
    val matrix = SAP_MATRIX_INIT.copyOf()
    val aux = IntArray(10)
    val work = IntArray(210)

    // Load input in reversed four-byte groups.
    for (i in 0 until 210) work[i] = block[blockOffset + ((i and 63) xor 3)].toInt() and 0xff

    // uint32 underflow changes the first of four scramble passes.
    for (i in 0 until 840) {
        val x = work[(((i.toLong() - 155) and 0xffffffffL) % 210L).toInt()]
        val y = work[(((i.toLong() - 57) and 0xffffffffL) % 210L).toInt()]
        val z = work[(((i.toLong() - 13) and 0xffffffffL) % 210L).toInt()]
        val w = work[i % 210]
        work[i % 210] = (rotl8(y, 5) + (rotl8(z, 3) xor w) - rotl8(x, 7)) and 0xff
    }

    sapNonlinearCircuit(hash, matrix, aux, work)

    val out = IntArray(16)
    // Include terminal work XORs directly in their folded output lanes.
    for (i in 0 until 3) out[i] = aux[i]
    for (i in 0 until 7) out[4 + i] = aux[3 + i]
    for (i in 0 until 16) out[i] = (out[i] + 0xe1) and 0xff
    out[3] = 0x3d
    out[11] = 0x3c
    out[10] = out[10] xor aux[3] xor 133

    for (i in 0 until 210) {
        var value = work[i]
        if (i < 35) value = value xor matrix[i]
        if (i < 20) value = value xor hash[i]
        out[i and 15] = out[i and 15] xor value
    }

    // Reverse scramble
    for (i in 0 until 256) {
        out[i and 15] = out[i and 15] xor
            rotl8(out[(i - 7) and 15], 1) xor
            rotl8(out[(i - 5) and 15], 6) xor
            rotl8(out[(i - 1) and 15], 5)
    }

    return ByteArray(16) { out[it].toByte() }
}

/** Arithmetic wraps as bytes unless explicitly promoted before division or indexing. */
private fun sapNonlinearCircuit(hash: IntArray, matrix: IntArray, aux: IntArray, work: IntArray) {
    // h/m/s read hash/matrix/seed through work; ma reads matrix through aux.
    fun hi(i: Int): Int = hash[i % 20]
    fun si(i: Int): Int = SAP_SEED[i % 21]
    fun h(i: Int): Int = hi(work[i])
    fun m(i: Int): Int = matrix[work[i] % 35]
    fun s(i: Int): Int = si(work[i])
    fun ma(i: Int): Int = matrix[aux[i] % 35]

    matrix[12] = (0x14 + (selectBits(92, work[64], work[99] / 3) and wideSeed(s(206), 4))) and 0xff
    work[4] = (2 * square(work[99] / 5)) and 0xff
    work[153] = work[153] xor ((square(m(203)) * work[190]) and 0xff)
    hash[3] = 0x13 xor ((s(205) shr 1) and 0x10)
    work[33] = (work[33] - (s(36) and 9.inv())) and 0xff
    aux[5] = (((m(67) and 2.inv()) or 1 or ((h(181) shr 6) and 2) or (hash[3] and 0x10)) - 15) and 0xff
    matrix[12] = 0x07
    work[2] = (work[2] - 64) and 0xff
    hash[19] = s(58)
    aux[4] = (92 - m(32)) and 0xff
    aux[9] = (m(15) + 0x9e) and 0xff
    work[34] = (work[34] + si(aux[9]) / 5) and 0xff
    hash[19] = (hash[19] + (0xe6 xor ((hi(aux[9]) shr 1) and 0x66))) and 0xff
    work[15] = work[15] xor ((3 * rotateOrZero(work[72], -s(190) and 7) - 9 * s(126)) and 0xff)
    hash[15] = hash[15] xor cube(m(181))
    matrix[4] = matrix[4] xor (work[202] / 3)
    matrix[1] = (matrix[1] + cube(majority((92 - hi(aux[4])) and 0xff, not8(work[105]), 0xc6))) and 0xff
    hash[19] = hash[19] xor (((224 or (s(92) and 27)) * m(41) / 3) and 0xff)
    work[140] = (work[140] + rotateOrZero(92, -work[5] and 7)) and 0xff
    matrix[12] = (matrix[12] + majority(not8(work[4]) xor m(12), work[182], 192)) and 0xff
    work[36] = (work[36] + 125) and 0xff
    work[124] = rotl8(majority(majority(work[138], hash[15], 74), h(43), 95), 4)
    val auxHash = hi(aux[9])
    aux[1] = 0x4c and (auxHash and ((s(68) shl 1) and 0xff)).inv() and 0xff
    aux[2] = (222 - majority(
        ((work[177] + s(79)) shr 1) and 0xff,
        (3 * work[148] / 5) and 0xff,
        matrix[1],
    )) and 0xff
    matrix[16] = (matrix[16] + (((ma(4) and 0x60.inv()) or auxHash or 8) - (rotl8(work[33], 2) or 128))) and 0xff
    hash[14] = hash[14] xor ma(2)
    work[19] = (work[19] + majority(
        rotateOrZero(si(h(201)), (m(112) shl 1) and 6),
        ((h(208) and 0x7c.inv()) or (h(164) and 0x7c)) / 5,
        37,
    )) and 0xff
    matrix[8] = rotateOrZero(140, -square(s(45)) and 7) xor aux[4]
    work[190] = 56
    work[53] = not8((h(83) or 204) / 5)
    hash[13] = (hash[13] + h(41)) and 0xff
    hash[10] = majority(ma(4), work[2], aux[2]) / 15
    aux[3] = (92 - square(0x28 or (ma(1) and (0x12 or (s(2) and 4))))) and 0xff
    val seedBits = si(aux[4])
    matrix[13] = matrix[13] xor seedBits
    aux[6] = (92 + square(majority((m(179) - 38) and 0xff, aux[2], 177))) and 0xff
    val expansionBits = majority((aux[3] + (aux[4] and 74)) and 0xff, not8(seedBits), 121)
    work[47] = work[47] xor ((m(89) + majority(expansionBits xor 0xa6, aux[4], 4)) and 0xff)
    aux[7] = (seedBits / 3 - ma(9) -
        (0x14 or (work[151] and ((aux[4] and 0x88) or 0x62)) or (aux[4] and 0x22))) and 0xff
    val expandedSelector = expansionBits xor ((aux[4] and 0xca) shr 1) xor 75
    aux[9] = (aux[9] + (0x80 or (majority(aux[7], work[151], 0x20) and 0x64) or
        (seedBits and 0x44) or (ma(9) and 0x1b))) and 0xff
    matrix[33] = matrix[33] xor work[26]
    matrix[30] = ((aux[9] / 3 - ((aux[4] and 8.inv()) or 0x13)) and 0xff) xor h(122)
    work[22] = (m(90) and 0x1b) or 0x44
    var wide = selectBits(71, matrix[expandedSelector % 35], si(aux[5]))
    matrix[18] = (matrix[18] + ((wide * wide * wide shr 1) and 0xff)) and 0xff
    matrix[5] = (matrix[5] - s(92)) and 0xff
    matrix[18] = matrix[18] xor ((selectBits(aux[3], ma(3), selectBits(16, m(183), work[41])) *
        selectBits(expandedSelector, h(59), work[17])) and 0xff)
    matrix[22] = (majority(
        selectBits(hash[14] or 28, (work[7] and 28) or 0x82, h(93)),
        rotateOrZero(ma(4), rotateOrZero(work[11], -m(28) and 7) and 7),
        matrix[33],
    ) + 74) and 0xff
    hash[15] = (hash[15] - majority(majority(aux[3], aux[4], 214), si(h(39) xor 217), aux[6])) and 0xff

    val hash9 = hi(aux[9])
    val indexedHash = hi(
        (((aux[4] / 3) - (aux[9] or work[22])) and 0xff) xor aux[6] xor
            (((m(57) or hash9) and (0x52 or (aux[9] and 0x0d))) or (((m(57) and hash9) or aux[9]) and 0x20)),
    )
    aux[6] = square(square(h(99))) or ma(9)
    aux[1] = (aux[1] + rotateOrZero(h(151) or s(202), h(50) and 7) + majority(
        h(4),
        ((selectBits(matrix[16], indexedHash, m(138)) + selectBits(17, work[33], s(39))) / 5) and 0xff,
        147,
    )) and 0xff
    aux[0] = selectBits(
        hash[10] and 7,
        ma(6) and h(209),
        selectBits(0x47, rotateOrZero(s(127), ma(6) and 7), (si(ma(5)) shl 1) and 0xff),
    )
    val selectedSquare = selectBits(198, square(m(14)), h(145) xor aux[0])
    val seed9 = si(aux[9])
    val hash3 = hi(aux[3])
    matrix[2] = (matrix[2] + ((((hash3 shl 1) and 0xff) and ((work[25] and 0x96) or (seed9 and 8))) or
        (seed9 and 0x40))) and 0xff
    matrix[14] = (matrix[14] - selectBits(34, work[97], ma(3) and (aux[0] xor m(100)))) and 0xff
    work[23] = work[23] xor ((majority(majority(s(17), hash3, aux[0]), work[50] / 3, 0x76) shl 1) and 0xff)
    hash[17] = 115
    hash[13] = ((majority(hi(aux[7]), work[10], 82) shr 1) and 0x68) or (h(39) and 0x17)
    matrix[33] = (matrix[33] - (work[113] and 9)) and 0xff
    matrix[28] = (matrix[28] - ((aux[3] and 0x20.inv()) or ((work[110] shr 1) and 0x20))) and 0xff
    work[95] = si(aux[3])
    hash[15] = majority((work[95] - 48) and 0xff, not8(work[184]), 189) and
        cube(majority(aux[7], si(aux[1]), 0xaa))
    matrix[22] = (matrix[22] + work[183]) and 0xff
    aux[4] = aux[4] xor ((3 * s(1)) and 0xff)
    aux[5] = (aux[5] + 198 * majority(s(178), ma(1), 209) * h(13) * (s(26) shr 1)) and 0xff
    aux[8] = selectBits(10, ma(3), ma(9))
    matrix[18] = (matrix[18] - selectBits(hash[15], aux[5] / 15, cube(hi(aux[6]) or 81))) and 0xff
    aux[1] = (aux[1] + ((si(hi(aux[1])) / 3 - h(160)) and 0xff)) and 0xff
    hash[16] = (147 - majority(
        aux[0],
        majority(s(69), work[172], (aux[2] - selectedSquare + 77) and 0xff),
        0xc2 or (aux[0] and 5),
    )) and 0xff
    hash[3] = (hash[3] - wideSeed(majority(s(155), work[105], 141), majority(s(168), h(29), 6) and 7)) and 0xff
    work[5] = rotateOrZero(0x38, -(h(61) / 5) and 7) xor (not8(ma(8)) / 5)
    work[198] = (work[198] + work[3]) and 0xff
    wide = 162 or ma(9)
    work[164] = (work[164] + ((wide * wide / 5) and 0xff)) and 0xff
    aux[2] = majority(rotateOrZero(139, -aux[5] and 6), hi(aux[3]), 12) or
        selectBits(95, cube(seed9), hi(aux[7]))
    matrix[12] = (matrix[12] + (16 or ((work[103] or 60) and (aux[2] or (work[103] and 32)))) / 3) and 0xff
    work[143] = (work[143] - (0x12 or (selectBits(
        aux[9],
        selectBits(matrix[8], work[35], aux[7]),
        aux[8] / 3,
    ) and (0x4d or ((work[172] shr 1) and 0x20))))) and 0xff
    matrix[29] = 162
    hash[15] = (hash[15] + majority(
        m(149) xor square(work[43]),
        selectBits(95, h(125), si(aux[1])) shr 1,
        115,
    )) and 0xff
    aux[9] = (aux[9] - hi(aux[7])) and 0xff
    hash[7] = (hash[7] - square(rotateOrZero(ma(5), -m(17) * (m(17) and 1)))) and 0xff
    matrix[8] = (matrix[8] + cube(s(202)) - work[184]) and 0xff
    hash[16] = (m(102) shl 1) and 0x84
    aux[6] = aux[6] xor (si(aux[7]) shr 1)
    hash[7] = (hash[7] - ((h(191) - selectBits(177, si(si(aux[1])), (s(80) shl 1) and 0xff)) and 0xff)) and 0xff
    hash[6] = h(119)
    hash[12] = ((hi(aux[8]) xor ((m(71) + m(15)) and 0xff)) and
        majority((work[118] and 0x2c.inv()) or 2, square(hi(aux[9])), 27))
    val digestIndex = selectBits(0xa9, (s(57) * 231) and 0xff, majority(work[32], ma(1), 23)) / 5
    val seedSample = si(aux[6])
    aux[5] = majority(
        (seedSample and 0x1c) or (h(82) and 0xa2) or (si(digestIndex) and 0x41),
        majority(cube(hi(aux[7])), work[82], 92),
        192,
    ) xor digestIndex
    matrix[25] = matrix[25] xor (((2 * hi(aux[9]) * work[5]) -
        (rotateOrZero(aux[4], seedSample and 7) and ((aux[3] + 110) and 0xff))) and 0xff)
}

// ---------------------------------------------------------------------------
// fairplay_message.go: inverse AES rounds with custom middle round keys, so
// javax.crypto/BouncyCastle AES cannot express this block transform.
// ---------------------------------------------------------------------------

private fun decryptFairPlayMessage(message: ByteArray, plaintext: ByteArray) {
    val mode = message[12].toInt() and 0xff
    for (step in 0 until 8) {
        // Mode 3 historically traverses the CBC chain backwards. This is
        // equivalent for separate buffers and also permits in-place use.
        val block = if (mode == 3) 7 - step else step
        val start = 16 + block * 16
        val state = IntArray(16) { message[start + it].toInt() and 0xff }
        decryptFairPlayMessageBlock(state, mode)

        for (i in 0 until 16) {
            val chain = if (block > 0) message[start - 16 + i].toInt() and 0xff else FAIRPLAY_MESSAGE_IV[mode * 16 + i]
            plaintext[block * 16 + i] = (state[i] xor chain).toByte()
        }
    }
}

private fun decryptFairPlayMessageBlock(state: IntArray, mode: Int) {
    xorAESRoundKey(state, FAIRPLAY_MESSAGE_ROUND_KEY10, 0)
    for (round in 9 downTo 1) {
        inverseAESShiftRows(state)
        for (i in 0 until 16) state[i] = INVERSE_AES_SBOX[state[i]]
        xorAESRoundKey(state, FAIRPLAY_MESSAGE_MIDDLE_KEYS, (mode * 9 + round - 1) * 16)
        inverseAESMixColumns(state)
    }
    inverseAESShiftRows(state)
    for (i in 0 until 16) state[i] = INVERSE_AES_SBOX[state[i]]
    xorAESRoundKey(state, FAIRPLAY_MESSAGE_ROUND_KEY0, 0)
}

/**
 * Applies the inverse of [decryptFairPlayMessage] to one 128-byte SAP value.
 * The output is the body stored at bytes 16:144 of an FPLY message.
 */
private fun encryptFairPlayMessage(mode: Int, plaintext: ByteArray, encrypted: ByteArray, offset: Int) {
    val chain = IntArray(16) { FAIRPLAY_MESSAGE_IV[mode * 16 + it] }
    for (block in 0 until 8) {
        val start = block * 16
        val state = IntArray(16) { (plaintext[start + it].toInt() and 0xff) xor chain[it] }
        encryptFairPlayMessageBlock(state, mode)
        for (i in 0 until 16) {
            encrypted[offset + start + i] = state[i].toByte()
            chain[i] = state[i]
        }
    }
}

private fun encryptFairPlayMessageBlock(state: IntArray, mode: Int) {
    xorAESRoundKey(state, FAIRPLAY_MESSAGE_ROUND_KEY0, 0)
    for (i in 0 until 16) state[i] = FORWARD_AES_SBOX[state[i]]
    aesShiftRows(state)
    for (round in 0 until 9) {
        aesMixColumns(state)
        xorAESRoundKey(state, FAIRPLAY_MESSAGE_MIDDLE_KEYS, (mode * 9 + round) * 16)
        for (i in 0 until 16) state[i] = FORWARD_AES_SBOX[state[i]]
        aesShiftRows(state)
    }
    xorAESRoundKey(state, FAIRPLAY_MESSAGE_ROUND_KEY10, 0)
}

private fun xorAESRoundKey(state: IntArray, key: IntArray, keyOffset: Int) {
    for (i in 0 until 16) state[i] = state[i] xor key[keyOffset + i]
}

private fun inverseAESShiftRows(state: IntArray) {
    val previous = state.copyOf()
    for (row in 0 until 4) {
        for (column in 0 until 4) state[4 * column + row] = previous[4 * ((column - row + 4) and 3) + row]
    }
}

private fun aesShiftRows(state: IntArray) {
    val previous = state.copyOf()
    for (row in 0 until 4) {
        for (column in 0 until 4) state[4 * column + row] = previous[4 * ((column + row) and 3) + row]
    }
}

private fun inverseAESMixColumns(state: IntArray) {
    for (column in 0 until 4) {
        val offset = column * 4
        val a = state[offset]
        val b = state[offset + 1]
        val c = state[offset + 2]
        val d = state[offset + 3]
        state[offset] = aesGFMultiply(a, 14) xor aesGFMultiply(b, 11) xor aesGFMultiply(c, 13) xor aesGFMultiply(d, 9)
        state[offset + 1] = aesGFMultiply(a, 9) xor aesGFMultiply(b, 14) xor aesGFMultiply(c, 11) xor aesGFMultiply(d, 13)
        state[offset + 2] = aesGFMultiply(a, 13) xor aesGFMultiply(b, 9) xor aesGFMultiply(c, 14) xor aesGFMultiply(d, 11)
        state[offset + 3] = aesGFMultiply(a, 11) xor aesGFMultiply(b, 13) xor aesGFMultiply(c, 9) xor aesGFMultiply(d, 14)
    }
}

private fun aesMixColumns(state: IntArray) {
    for (column in 0 until 4) {
        val offset = column * 4
        val a = state[offset]
        val b = state[offset + 1]
        val c = state[offset + 2]
        val d = state[offset + 3]
        state[offset] = aesGFMultiply(a, 2) xor aesGFMultiply(b, 3) xor c xor d
        state[offset + 1] = a xor aesGFMultiply(b, 2) xor aesGFMultiply(c, 3) xor d
        state[offset + 2] = a xor b xor aesGFMultiply(c, 2) xor aesGFMultiply(d, 3)
        state[offset + 3] = aesGFMultiply(a, 3) xor b xor c xor aesGFMultiply(d, 2)
    }
}

private fun aesGFMultiply(factor: Int, multiplier: Int): Int {
    var a = factor
    var b = multiplier
    var product = 0
    while (b != 0) {
        if (b and 1 != 0) product = product xor a
        val high = a and 0x80
        a = (a shl 1) and 0xff
        if (high != 0) a = a xor 0x1b
        b = b shr 1
    }
    return product and 0xff
}

// ---------------------------------------------------------------------------
// fpsap.go: white-box networks and the m2/m3 exchange value.
// ---------------------------------------------------------------------------

/** Derives the white-box seed from both halves of an exchange. */
private fun fpsapDescriptorForSAP(m3SAP: ByteArray, m2SAP: ByteArray): ByteArray {
    val padded = ByteArray(320)
    var offset = 0
    FPSAP_DESCRIPTOR_PREFIX.copyInto(padded, offset); offset += FPSAP_DESCRIPTOR_PREFIX.size
    m3SAP.copyInto(padded, offset); offset += m3SAP.size
    m2SAP.copyInto(padded, offset); offset += m2SAP.size
    FPSAP_DESCRIPTOR_SUFFIX.copyInto(padded, offset); offset += FPSAP_DESCRIPTOR_SUFFIX.size
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
            substituted[output] = FPSAP_FIRST_TABLES.roundSubstitution[bank][input].substitute(state[input])
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
            substituted[output] = FPSAP_SECOND_TABLES.roundSubstitution[bank][output].substitute(state[source]) xor
                masks[bank * 16 + output]
        }
        fpsapMix(FPSAP_SECOND_TABLES, state, substituted)
    }
    val out = IntArray(16)
    for (output in 0 until 16) {
        val source = FPSAP_SECOND_POSITION_MAP[output]
        out[output] = FPSAP_SECOND_TABLES.finalSubstitution[output].substitute(state[source]) xor
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
                mixed = mixed xor tables.mixColumns[inputByte][outputByte].mix(substituted[offset + inputByte])
            }
            state[offset + outputByte] = mixed
        }
    }
}

private fun fpsapExchangeForSAP(m3SAP: ByteArray, m2SAP: ByteArray): ByteArray =
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

// ---------------------------------------------------------------------------
// fairplay_crypto.go: session key derivation and the 72-byte ekey record.
// ---------------------------------------------------------------------------

private fun deriveFairPlayWrappingKey(receiverSAP: ByteArray, message: ByteArray): ByteArray {
    val decrypted = ByteArray(128)
    decryptFairPlayMessage(message, decrypted)

    // The KDF input is a 290-byte protocol record followed by ordinary MD5
    // padding. The compression itself is FairPlay's modified MD5/SAP-hash
    // combination, not a standard MD5 digest.
    val material = ByteArray(320)
    var offset = 0
    FAIRPLAY_KDF_PREFIX.copyInto(material, offset); offset += FAIRPLAY_KDF_PREFIX.size
    decrypted.copyInto(material, offset); offset += decrypted.size
    receiverSAP.copyInto(material, offset); offset += receiverSAP.size
    FAIRPLAY_KDF_SUFFIX.copyInto(material, offset); offset += FAIRPLAY_KDF_SUFFIX.size
    material[offset] = 0x80.toByte()
    writeLittleEndian64(material, material.size - 8, offset.toLong() * 8)

    val state = fairplayWordsFromLittleEndian(FAIRPLAY_INITIAL_SESSION_KEY)
    var blockOffset = 0
    while (blockOffset < material.size) {
        val modified = fairplayMD5Compress(state, material, blockOffset, MUTATION_KDF)
        val hashed = fairplaySAPHash(material, blockOffset)
        for (word in 0 until 4) state[word] = modified[word] + readLittleEndian32(hashed, word * 4)
        blockOffset += 64
    }
    return fairplayWordsBigEndian(state)
}

/**
 * Emits the 72-byte AirPlay v3 record produced by Apple's FairPlay sender:
 *
 *     [0:16]  FPLY encrypted-key header
 *     [16:32] per-key random mask
 *     [32:36] big-endian raw-key length (16)
 *     [36:56] HMAC-SHA1(session MAC key, record[0:36] || raw key)
 *     [56:72] AES-wrapped (raw key XOR mask)
 */
private fun wrapFairPlayKey(receiverSAP: ByteArray, m3: ByteArray, rawKey: ByteArray, mask: ByteArray): ByteArray {
    validateFPSAPRecord(m3, 3, 152, "m3")
    val mode = m3[12].toInt() and 0xff
    if (mode >= FAIRPLAY_MODE_COUNT) throw IllegalStateException("unsupported FairPlay mode $mode")

    val ekey = ByteArray(72)
    FAIRPLAY_EKEY_HEADER.copyInto(ekey, 0)
    mask.copyInto(ekey, 16)
    writeBigEndian32(ekey, 32, rawKey.size)

    val wrappingKey = deriveFairPlayWrappingKey(receiverSAP, m3)
    val masked = ByteArray(16) { (rawKey[it].toInt() xor mask[it].toInt()).toByte() }
    val cipher = AESEngine.newInstance()
    cipher.init(true, KeyParameter(wrappingKey))
    cipher.processBlock(masked, 0, ekey, 56)

    val senderSAP = ByteArray(128)
    decryptFairPlayMessage(m3, senderSAP)
    val macKey = fpsapDescriptorForSAP(senderSAP, receiverSAP)
    val mac = HMac(SHA1Digest())
    mac.init(KeyParameter(macKey))
    mac.update(ekey, 0, 36)
    mac.update(rawKey, 0, rawKey.size)
    mac.doFinal(ekey, 36)
    return ekey
}

// ---------------------------------------------------------------------------
// fpsap.go: FPLY record framing and the session lifecycle.
// ---------------------------------------------------------------------------

private fun newFPSAPRecord(messageType: Int, payloadLength: Int): ByteArray {
    val record = ByteArray(12 + payloadLength)
    record[0] = 'F'.code.toByte()
    record[1] = 'P'.code.toByte()
    record[2] = 'L'.code.toByte()
    record[3] = 'Y'.code.toByte()
    record[4] = 3
    record[5] = 1
    record[6] = messageType.toByte()
    record[7] = 0
    writeBigEndian32(record, 8, payloadLength)
    return record
}

private fun toHex(data: ByteArray, from: Int, to: Int): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder((to - from) * 2)
    for (i in from until to) {
        val value = data[i].toInt() and 0xff
        out.append(digits[value ushr 4]).append(digits[value and 15])
    }
    return out.toString()
}

private fun validateFPSAPRecord(record: ByteArray, messageType: Int, payloadLength: Int, label: String) {
    val wantLength = 12 + payloadLength
    if (record.size != wantLength) {
        throw IllegalArgumentException("invalid $label: length ${record.size}, want $wantLength")
    }
    if (record[0] != 'F'.code.toByte() || record[1] != 'P'.code.toByte() ||
        record[2] != 'L'.code.toByte() || record[3] != 'Y'.code.toByte()
    ) {
        throw IllegalArgumentException("invalid $label: invalid magic ${toHex(record, 0, 4)}")
    }
    if (record[4].toInt() != 3 || record[5].toInt() != 1 ||
        (record[6].toInt() and 0xff) != messageType || record[7].toInt() != 0
    ) {
        throw IllegalArgumentException("invalid $label: invalid version/type ${toHex(record, 4, 8)}")
    }
    val declared = readBigEndian32(record, 8)
    if (declared != payloadLength) {
        throw IllegalArgumentException("invalid $label: declared payload length $declared, want $payloadLength")
    }
}

private fun decryptFPSAPBody(mode: Int, source: ByteArray, offset: Int): ByteArray {
    if (mode >= FAIRPLAY_MODE_COUNT) throw IllegalArgumentException("unsupported FairPlay mode $mode")
    val message = ByteArray(144)
    message[12] = mode.toByte()
    source.copyInto(message, 16, offset, offset + 128)
    val out = ByteArray(128)
    decryptFairPlayMessage(message, out)
    return out
}

/**
 * One FairPlay SAP authentication attempt.
 *
 * Apple's sender creates a single opaque context before m1 and reuses it for m3
 * and key wrapping; the same state is kept together here. The native
 * implementation fills the local SAP from an arc4random-seeded internal
 * generator and then overwrites its first two bytes with 00 01. Using the
 * caller's entropy source for the remaining 126 opaque bytes preserves those
 * protocol semantics without porting that PRNG.
 */
class FairPlaySapSession(private val random: SecureRandom = SecureRandom()) {
    private val localSAP = ByteArray(128)
    private val remoteSAP = ByteArray(128)
    private var m3: ByteArray? = null

    init {
        localSAP[1] = 1
        val entropy = ByteArray(126)
        random.nextBytes(entropy)
        entropy.copyInto(localSAP, 2)
    }

    /** First `/fp-setup` body to POST (with header `X-Apple-ET: 32`). */
    fun message1(): ByteArray {
        val m1 = newFPSAPRecord(1, FPSAP_M1_PAYLOAD.size)
        FPSAP_M1_PAYLOAD.copyInto(m1, 12)
        return m1
    }

    /** Feeds the receiver's m2 reply and returns the m3 body to POST next. */
    fun exchangeM3(m2: ByteArray): ByteArray {
        validateFPSAPRecord(m2, 2, 130, "m2")
        if (m2[12].toInt() != 2) {
            throw IllegalArgumentException("invalid m2 payload marker ${m2[12].toInt() and 0xff}")
        }
        val mode = m2[13].toInt() and 0xff
        if (mode >= FAIRPLAY_MODE_COUNT) throw IllegalArgumentException("m2 selected unsupported mode $mode")

        val m3 = newFPSAPRecord(3, 152)
        m3[12] = mode.toByte()
        FPSAP_M3_LABEL.copyInto(m3, 13)
        encryptFairPlayMessage(mode, localSAP, m3, 16)

        val m2SAP = decryptFPSAPBody(mode, m2, 14)
        fpsapExchangeForSAP(localSAP, m2SAP).copyInto(m3, 144)
        m2SAP.copyInto(remoteSAP)
        this.m3 = m3
        return m3.copyOf()
    }

    /** Feeds the receiver's m4 reply to finish the handshake. */
    fun finish(m4: ByteArray) {
        val m3 = this.m3 ?: throw IllegalStateException("m3 has not been generated")
        validateFPSAPRecord(m4, 4, 20, "m4")
        for (i in 0 until 20) {
            if (m4[12 + i] != m3[144 + i]) {
                throw IllegalStateException("m4 confirmation does not match m3")
            }
        }
    }

    /**
     * Wraps a 16-byte stream key once the handshake has completed.
     *
     * Draws 32 bytes from the session entropy source: the stream IV first, then
     * the per-key mask that the ekey record carries at bytes 16:32.
     */
    fun wrapKey(streamKey: ByteArray): FairPlayKeys {
        val m3 = this.m3 ?: throw IllegalStateException("m3 has not been generated")
        require(streamKey.size == 16) { "stream key length ${streamKey.size}, want 16" }
        val eiv = ByteArray(16)
        random.nextBytes(eiv)
        val mask = ByteArray(16)
        random.nextBytes(mask)
        return FairPlayKeys(wrapFairPlayKey(remoteSAP, m3, streamKey, mask), eiv)
    }
}
