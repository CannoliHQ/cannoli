package dev.cannoli.scorza.config

internal object LegacyShaders {
    val FILES: Map<String, Set<String>> = mapOf(
        "crt-aperture/crt-aperture.glslp" to setOf("f8dd3d1c40e946255802378d8adfc228c57854972556775d414e5d6385848038"),
        "crt-aperture/shaders/crt-aperture.glsl" to setOf("d7c20c972ae0cd9790280d8a070f1bd96bb4b9d90b4718a94c6296a58cc1f81e"),
        "crt-cannoli/crt-cannoli-lite.glslp" to setOf("66054b99c4db7b36c25bc13b3aa31259b2d0219389fbac8c8063a5ef60cf4a18"),
        "crt-cannoli/crt-cannoli.glslp" to setOf("14a53bce410b1f8947f8fce7b16b95f171a0258ca4f4dbb61a3992f2d8ed2dc0"),
        "crt-cannoli/shaders/crt-composite-lite.glsl" to setOf(
            "41895bf7df8197e8971de5217c3dc4f14cfcd345a1cdb3b38d62b7a5a8ab5ce5",
            "fa8ba991a4e368d9be18ae9bbaa78ae9ad1bd76e1265ec88bb24db6b1d654500",
        ),
        "crt-cannoli/shaders/crt-composite.glsl" to setOf(
            "18c43d446c273a2a4db7a68a5a9e0d233dadf5ae5ad6b9a8a07b38fb1a9dea38",
            "8277ff4f745506c2c9900e61ff9d4e2ea31e567f8aae5f265b425c9abd53be1e",
        ),
        "crt-cannoli/shaders/kawase-blur-d0.glsl" to setOf(
            "17529c750399f27e4bd9711f081aa1eb55c1798b51e740ca14dee653861ed068",
            "b9f8da81d48bf6f4c2dc5e4344db117ccea817b6e0c6267bf91a27ec439d8d52",
        ),
        "crt-cannoli/shaders/kawase-blur-d1.glsl" to setOf(
            "3a53e26cb5c9b7c88ee9ce90cc0983df66b165a0cc974cf496ee852ea6266b80",
            "d1376b96e1fbde4e2e024eaee83c6d4cf8606b3a569ed28b45844fdd646f0f8f",
        ),
        "crt-cannoli/shaders/kawase-blur-d2.glsl" to setOf(
            "690249e6f208812854507a85cc2469702f06f2067c97b3fe66ab1ec107267d3e",
            "7e814c650fd4ec58e99f8c8391bad1fd84833970518bc681b34750e86cc5ae4e",
        ),
        "crt-cannoli/shaders/kawase-blur-d3.glsl" to setOf(
            "13df5249d701a6371c62713eacc4c7a68bf18a96ad1a4e73f4ec4988050ff64b",
            "4ed8965c064ea5fcaf9b349531da812d338f86ff1b017994e726f0b5c704bf5d",
        ),
        "crt-easymode/crt-easymode.glslp" to setOf("97e7fffc8330622632c82e070d449a0f5ccc9e0e824bfa701035a515da54c1f1"),
        "crt-easymode/crt-easymode.slangp" to setOf("3e624916ca4e3bc8660c0b00b3abcb262b03b2699137a03d8171d936054a1fd5"),
        "crt-easymode/shaders/crt-easymode.glsl" to setOf("ed27bab460c53d98fcf727333210de0d746c957c86463028d8c0e4c15e29f3ab"),
        "crt-easymode/shaders/crt-easymode.slang" to setOf("cacb1595c7988619c29b46cc283e2140f25cf14a9d104fcf6c28f197ee4b29a3"),
        "crt-geom/crt-geom.glslp" to setOf("993270e48660d05800dfb855d8d1a9bab4e405fe6a26b34e82584144d9dd9265"),
        "crt-geom/shaders/crt-geom.glsl" to setOf("6f4c98cc71e8d021d141f3b2f517bbbfeb93a6c9701469c5c855a5c67aaad2e9"),
        "crt-lottes-fast/crt-lottes-fast.glslp" to setOf("ad948a8dc52c60aa4b5c3fb05046d7e1b41de7265979ff94ab52cf5d3975a9f6"),
        "crt-lottes-fast/shaders/crt-lottes-fast.glsl" to setOf("bff33e948d72fd809c832e213672c9ee455052bb673887c345dd6b82be02dd78"),
        "dot/dot.glslp" to setOf("56c29b00d7382182bec2879d60edd99c054ff0dfd39f57a49fa9aaa212ae9f29"),
        "dot/shaders/dot.glsl" to setOf("d7b922663e5c74b50275407fd98acdce8bc7f0496cd854b38d3826678a0ecb48"),
        "lcd3x/lcd3x.glslp" to setOf("7c9848bd6b89b12d02d21b4895b2630e14134f53c735614d4357d94bb8671ae0"),
        "lcd3x/lcd3x.slangp" to setOf("73aad2f4b1b548d9ea566cd3c72dbad433594235aed00115013b1ec46bf4c2d9"),
        "lcd3x/shaders/lcd3x.glsl" to setOf("1ca0fb49b35fe3415b0eeb78518e5042a87f8b26f0ae76bf7b99fd56a5c7762e"),
        "lcd3x/shaders/lcd3x.slang" to setOf("58cb684d42d3ab336aefedb7f961bf551428d21d7371d2fa9eae64cbdaafcdec"),
        "scanline-fract/scanline-fract.slangp" to setOf("66cb16b741b669f7fd5123a9303c441d1a9977ec32eec6e9adc519f496bbd878"),
        "scanline-fract/shaders/scanline-fract.slang" to setOf("86bf8f26d35b109f41dacbb1a942e97ab7b3abce62badc57bf0afc083f8fd3e3"),
        "scanline/scanline.glslp" to setOf("ebc81ba57efbd55ce4d61c20a0e81a0e176b6b7634f847e6914f80d0c25439f1"),
        "scanline/shaders/scanline.glsl" to setOf("10010f634ae0370b3e2f3e55c4587500385363d295f55786f410891a406b93b4"),
        "sharp-bilinear-simple/shaders/sharp-bilinear-simple.glsl" to setOf("2ef97991a77492d97b71cc359fcc26c6537c1bbb644fe61b1572d0bfc46038c9"),
        "sharp-bilinear-simple/sharp-bilinear-simple.glslp" to setOf("6b08c98557454eae4131d8169bba39b714f4a117aaeaf5314a93673d7e44fd30"),
        "sharp-bilinear/shaders/sharp-bilinear.slang" to setOf("89c30132f1530466676a3be76fcdc5af42f808cd56df6571ff65b64e7073ab7e"),
        "sharp-bilinear/sharp-bilinear.slangp" to setOf("059a79b17fe4635420f032cb8c1c42072bf7e9104052f3a6cce564cca17f9b5d"),
        "zfast-crt/shaders/zfast_crt.glsl" to setOf("b6edb36eb2c5e991687b8a2588723e13c809c8b923f47e950a62c2517bbb1f38"),
        "zfast-crt/shaders/zfast_crt/zfast_crt_composite.slang" to setOf("f65a6c0eaa2080c53c8769f5b1d71fd5baf577d7a4852b6eefe56dfb40fa7f12"),
        "zfast-crt/zfast-crt-composite.slangp" to setOf("639e0519cff5857f26ccd77ce3550d14e427584e9c0cc5a14ed89faee71f4475"),
        "zfast-crt/zfast-crt.glslp" to setOf("45a1a09d8c2888e1a323cce0686cea9763c4f672411f4fbe8fa91592bf159e1d"),
        "zfast-lcd/shaders/zfast_lcd.glsl" to setOf("6f9a537d6275fba21c799fa3fddb6cdc5d6bbd7f6997938380c1b1985cfaa8d4"),
        "zfast-lcd/shaders/zfast_lcd.slang" to setOf("49d34a36c794d831fdf169b51ae4cd028a6bfc6e9e630b8ed6fe0a6905d5f917"),
        "zfast-lcd/zfast-lcd.glslp" to setOf("6a96fbdf88b19a3f36601964418d457084236fb0b6858895cbb0f6e128829e5f"),
        "zfast-lcd/zfast-lcd.slangp" to setOf("480eecec22dac09b947ca9822738100e6cb343436008d37759ea1f77a68c44eb"),
    )
}
