package com.example.nova

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дефект, ради которого написаны эти проверки: пользователь выбирал MASQUE, а
 * подключение шло по WARP со счётчиком встроенных профилей. Причина была не в
 * самом MASQUE — фаза просто не начиналась, потому что оптимизация быстрого
 * старта откладывала получение identity, а без identity фазы нет.
 */
class MasqueStartPolicyTest {

    /** Обычный Wi-Fi, режим «Авто», ничего особенного не настроено. */
    private val ordinaryWifiAuto = MasqueStartPolicy.Inputs(
        masqueChosenExplicitly = false,
        hasCachedIdentity = false,
        hasStrongVerifiedMasque = false,
        hasUserImportedWarpProfiles = false,
        messengerFastStart = false,
        aggressiveFastStart = false,
        underlyingNetworkMetered = false,
        restrictedMobileNetwork = false,
        diagnosticsMode = false,
        operaBootstrapCycle = false,
        cooldownActive = false,
        hasFreshLastSuccess = false,
    )

    @Test
    fun `явный выбор MASQUE не откладывается на обычном Wi-Fi`() {
        val auto = MasqueStartPolicy.decide(ordinaryWifiAuto)
        // Так вело себя приложение до исправления — и это было верно для «Авто».
        assertTrue(auto.deferForOrdinaryWifi)
        assertTrue(auto.deferIdentityPreparation)

        val explicit = MasqueStartPolicy.decide(ordinaryWifiAuto.copy(masqueChosenExplicitly = true))
        assertFalse(explicit.deferIdentityPreparation)
        assertTrue(explicit.masqueFirst)
    }

    @Test
    fun `явный выбор MASQUE не откладывается ради импортированных профилей`() {
        val withImported = ordinaryWifiAuto.copy(
            hasUserImportedWarpProfiles = true,
            hasCachedIdentity = true,
        )
        assertTrue(MasqueStartPolicy.decide(withImported).deferForUserImported)

        val explicit = MasqueStartPolicy.decide(withImported.copy(masqueChosenExplicitly = true))
        assertFalse(explicit.deferForUserImported)
        assertFalse(explicit.deferIdentityPreparation)
        assertTrue(explicit.masqueFirst)
    }

    @Test
    fun `явный выбор MASQUE не откладывается ради мессенджеров`() {
        val messenger = ordinaryWifiAuto.copy(
            messengerFastStart = true,
            aggressiveFastStart = true,
            hasCachedIdentity = true,
        )
        assertTrue(MasqueStartPolicy.decide(messenger).deferForMessenger)

        val explicit = MasqueStartPolicy.decide(messenger.copy(masqueChosenExplicitly = true))
        assertFalse(explicit.deferForMessenger)
        assertFalse(explicit.deferIdentityPreparation)
    }

    @Test
    fun `явный выбор MASQUE не отменяется недавними срывами`() {
        val cooldown = ordinaryWifiAuto.copy(
            underlyingNetworkMetered = true,
            cooldownActive = true,
            hasCachedIdentity = true,
        )
        assertTrue(MasqueStartPolicy.decide(cooldown).skipForCooldown)

        val explicit = MasqueStartPolicy.decide(cooldown.copy(masqueChosenExplicitly = true))
        assertFalse(explicit.skipForCooldown)
    }

    @Test
    fun `у уважения к явному выбору есть предел`() {
        val explicit = ordinaryWifiAuto.copy(masqueChosenExplicitly = true, hasCachedIdentity = true)

        // Пока срывов немного, выбор пользователя сильнее.
        val limit = MasqueStartPolicy.EXPLICIT_CHOICE_FAILURE_LIMIT
        val tolerated = MasqueStartPolicy.decide(explicit.copy(failureStreak = limit - 1))
        assertFalse(tolerated.skipForCooldown)
        assertFalse(tolerated.brokenDespiteExplicitChoice)

        // Дальше повторы превратились бы в цикл переподключений.
        val broken = MasqueStartPolicy.decide(explicit.copy(failureStreak = limit))
        assertTrue(broken.brokenDespiteExplicitChoice)
        assertTrue(broken.skipForCooldown)
    }

    @Test
    fun `уступка WARP не вечная и истекает вместе с серией срывов`() {
        val explicit = ordinaryWifiAuto.copy(masqueChosenExplicitly = true, hasCachedIdentity = true)
        val limit = MasqueStartPolicy.EXPLICIT_CHOICE_FAILURE_LIMIT

        // Счётчик срывов обнуляется только успешным MASQUE. Если бы уступка не
        // истекала, выбранный протокол больше не пробовался бы никогда, а успеху
        // взяться неоткуда: попыток нет.
        val expired = MasqueStartPolicy.decide(
            explicit.copy(failureStreak = limit, explicitLockoutFresh = false)
        )
        assertFalse(expired.brokenDespiteExplicitChoice)
        assertFalse(expired.skipForCooldown)
        assertTrue(expired.masqueFirst)
    }

    @Test
    fun `срывы в режиме Авто не считаются поломкой явного выбора`() {
        val auto = ordinaryWifiAuto.copy(hasCachedIdentity = true, failureStreak = 9)
        assertFalse(MasqueStartPolicy.decide(auto).brokenDespiteExplicitChoice)
    }

    @Test
    fun `регистрации дают полный бюджет только когда MASQUE выбран и кэша нет`() {
        assertFalse(MasqueStartPolicy.decide(ordinaryWifiAuto).thoroughRegistration)

        val explicitNoCache = ordinaryWifiAuto.copy(masqueChosenExplicitly = true)
        assertTrue(MasqueStartPolicy.decide(explicitNoCache).thoroughRegistration)

        // С готовым identity регистрация не нужна, растягивать нечего.
        val explicitCached = explicitNoCache.copy(hasCachedIdentity = true)
        assertFalse(MasqueStartPolicy.decide(explicitCached).thoroughRegistration)
    }

    @Test
    fun `в диагностике MASQUE не участвует и ничего не откладывает`() {
        val diagnostics = ordinaryWifiAuto.copy(
            diagnosticsMode = true,
            hasStrongVerifiedMasque = true,
            hasUserImportedWarpProfiles = true,
            messengerFastStart = true,
            aggressiveFastStart = true,
        )
        val decision = MasqueStartPolicy.decide(diagnostics)
        assertFalse(decision.masqueFirst)
        assertFalse(decision.deferIdentityPreparation)
    }

    @Test
    fun `bootstrap-цикл Opera не уходит в MASQUE`() {
        val bootstrap = ordinaryWifiAuto.copy(
            operaBootstrapCycle = true,
            hasStrongVerifiedMasque = true,
        )
        val decision = MasqueStartPolicy.decide(bootstrap)
        assertFalse(decision.masqueFirst)
        assertFalse(decision.deferIdentityPreparation)
    }

    @Test
    fun `в режиме Авто сильные проверенные профили по-прежнему дают MASQUE-first`() {
        val strong = ordinaryWifiAuto.copy(
            hasStrongVerifiedMasque = true,
            hasCachedIdentity = true,
        )
        assertTrue(MasqueStartPolicy.decide(strong).masqueFirst)

        // Импортированные пользователем профили важнее накопленной статистики.
        val withImported = strong.copy(hasUserImportedWarpProfiles = true)
        assertFalse(MasqueStartPolicy.decide(withImported).masqueFirst)
    }

    @Test
    fun `в режиме Авто cooldown действует только на мобильной или ограниченной сети`() {
        val wifiCooldown = ordinaryWifiAuto.copy(cooldownActive = true, hasCachedIdentity = true)
        assertFalse(MasqueStartPolicy.decide(wifiCooldown).skipForCooldown)

        val meteredCooldown = wifiCooldown.copy(underlyingNetworkMetered = true)
        assertTrue(MasqueStartPolicy.decide(meteredCooldown).skipForCooldown)

        // Свежий успешный маршрут — повод не считать транспорт сломанным.
        assertFalse(
            MasqueStartPolicy.decide(meteredCooldown.copy(hasFreshLastSuccess = true)).skipForCooldown
        )
        // Как и наличие сильных проверенных MASQUE-профилей.
        assertFalse(
            MasqueStartPolicy.decide(meteredCooldown.copy(hasStrongVerifiedMasque = true)).skipForCooldown
        )
    }

    /**
     * Дефект, ради которого написаны проверки ниже: пользователь выбирал встроенные
     * WARP, а счётчик показывал «x/50», потом «x/4» и только через полминуты начинался
     * настоящий перебор. Полминуты уходили на MASQUE, который на этой сети не
     * поднимался ни разу — QUIC резался, и все четыре кандидата падали по таймауту.
     */
    private val masqueBrokenOnThisNetwork = ordinaryWifiAuto.copy(
        hasCachedIdentity = true,
        // Именно сильные verified-профили и ставили MASQUE первым.
        hasStrongVerifiedMasque = true,
        failureStreak = MasqueStartPolicy.AUTO_FIRST_FAILURE_LIMIT,
        explicitLockoutFresh = true,
    )

    @Test
    fun `в режиме Авто серия срывов убирает MASQUE с первого места`() {
        val stillTrusted = masqueBrokenOnThisNetwork.copy(failureStreak = 1)
        assertTrue(MasqueStartPolicy.decide(stillTrusted).masqueFirst)
        assertFalse(MasqueStartPolicy.decide(stillTrusted).skipForCooldown)

        val decision = MasqueStartPolicy.decide(masqueBrokenOnThisNetwork)
        assertFalse(decision.masqueFirst)
        // Мало убрать с первого места: оставленный в цепочке MASQUE сожжёт своё время
        // просто позже, поэтому в этом цикле он пропускается целиком.
        assertTrue(decision.skipForCooldown)
    }

    @Test
    fun `отсрочка в режиме Авто временная`() {
        // Счётчик срывов протух — полный цикл MASQUE возвращается сам, без действий
        // пользователя. Иначе одна неудачная сеть выключала бы транспорт навсегда.
        val expired = masqueBrokenOnThisNetwork.copy(explicitLockoutFresh = false)
        assertTrue(MasqueStartPolicy.decide(expired).masqueFirst)
        assertFalse(MasqueStartPolicy.decide(expired).skipForCooldown)
    }

    @Test
    fun `явный выбор MASQUE получает полный скан кандидатов`() {
        // Дефект: выбранный пользователем MASQUE пробовал МЕНЬШЕ вариантов, чем «Авто».
        // Быстрый старт отказывается от скана и берёт трёх anycast-соседей на одном
        // адресе, все по UDP, — на сети, режущей QUIC, шансов не было вовсе.
        assertFalse(MasqueStartPolicy.decide(ordinaryWifiAuto).thoroughCandidateScan)
        assertTrue(
            MasqueStartPolicy.decide(
                ordinaryWifiAuto.copy(masqueChosenExplicitly = true)
            ).thoroughCandidateScan
        )
        // Кэш identity к полноте перебора отношения не имеет: он про регистрацию.
        assertTrue(
            MasqueStartPolicy.decide(
                ordinaryWifiAuto.copy(masqueChosenExplicitly = true, hasCachedIdentity = true)
            ).thoroughCandidateScan
        )
    }

    @Test
    fun `явный выбор MASQUE серией срывов в режиме Авто не задевается`() {
        // У явного выбора свой предел, выше и со своим сообщением. Порог «Авто» не
        // должен отбирать у пользователя выбранный им протокол раньше времени.
        val explicit = masqueBrokenOnThisNetwork.copy(masqueChosenExplicitly = true)
        val decision = MasqueStartPolicy.decide(explicit)
        assertTrue(decision.masqueFirst)
        assertFalse(decision.skipForCooldown)
        assertFalse(decision.brokenDespiteExplicitChoice)
    }
}
