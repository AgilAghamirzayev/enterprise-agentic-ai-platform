# Arxitektura ilə implementasiyanın müqayisəsi

Mənbə 0.1.0 versiyalı texniki dizayndır. Aşağıdakı nömrələr orijinaldakı bölmələrə uyğundur. Dizaynın 6-cı bölməsi əsas və seçimli texnologiyaları, 9-cu bölməsi MVP və v1 agent növlərini, 31-ci bölməsi isə gələcək istismar hədəflərini müəyyənləşdirir.

| Bölmə | Kodda qarşılığı və yoxlama |
| --- | --- |
| 1. Məhsulun məqsədi | Java tətbiqi SDK-nı ayrıca istifadə edə bilir. Öz serverində tam platforma istəyən operator API, worker, PostgreSQL, Temporal və Studio-nu Compose ilə başladır. [İlk agent](../../examples/hello-agent/) |
| 2. İstifadəçilər | Developer versiya dərc edir, operator icra başladır, reviewer konkret əməliyyatı təsdiqləyir, auditor icra və təhlükəsizlik qeydlərini oxuyur. OIDC rolları və workspace üzvlüyü birlikdə yoxlanılır. [İcazələr](../../platform-server/src/main/java/io/agentplatform/server/WorkspaceScope.java) |
| 3. Məhsul sərhədi | SDK, runtime və Studio ayrıca yığılır. Studio serverin açıq REST müqaviləsindən istifadə edir. [Modullar](../../pom.xml) |
| 4. Prinsiplər | AP-01/02/05/11 üçün starter və provider interfeysləri, AP-03/07/10/15 üçün icazə yoxlaması, büdcə və insan təsdiqi, AP-04/06 üçün Temporal və saxlanılan nəticələr, AP-08/09 üçün icra izi və uçot mövcuddur. AP-12/13/14 Compose, versiyalı API və köhnə workflow tarixçəsinin replay testi ilə yoxlanılır. [Qərarlar](../adr/README.md) |
| 5. Üç icra sahəsi | API konfiqurasiyanı dərc edir və başlatma əmrini tranzaksiyada yazır. Worker əvvəlcədən dərc edilmiş versiyaları icra edir. PostgreSQL məlumatları, Temporal isə orkestrasiya tarixçəsini saxlayır. [Dispatch](../../platform-server/src/main/java/io/agentplatform/server/DispatchQueue.java) |
| 6. Texnologiyalar | Java 21, Spring Boot, Spring AI, Maven, Temporal, PostgreSQL/pgvector, Liquibase, Spring Security, OpenAPI, Micrometer və Next.js istifadə olunur. GitHub Actions üçün qorunan workflow faylı cihaz siyasəti ilə bloklanıb. [Yerli yoxlama](../../scripts/verify.sh) |
| 7. Agent reyestri | Qaralama dəyişdirilə bilir. Dərc əməliyyatı revision-u yoxlayır. Dərc edilmiş məzmun dəyişməzdir. Eyni adın yeni versiyası əvvəlki icranın istinadını dəyişmir. [Reyestr](../../platform-server/src/main/java/io/agentplatform/server/DefinitionController.java) |
| 8. SDK | Tipli Java agenti, annotation discovery, model, alət, yaddaş, bilik və cancellation interfeysləri var. Yerli icra ilə paylanmış icra eyni agent müqaviləsindən istifadə edə bilir. [SDK təlimatı](../developer-guide/java-sdk.md) |
| 9. Agent növləri | Single shot, tool calling, RAG, sequential, parallel, supervisor və router nümunələri mövcuddur. Məhdud planlaşdırma və qiymətləndirmə dövrəsi, iç-içə delegasiya, CloudEvents və CronJob ayrıca yoxlanıla bilər. [Nümunələr](../../examples/) |
| 10. Runtime büdcəsi | İterasiyalar, addımlar, çağırışlar, delegasiya dərinliyi, paralellik, vaxt, token və qiymət limitləri yoxlanılır. Workspace xərci ana tenantın aylıq limitinə də yazılır. Qiyməti məlum olmayan çağırış USD limitindən keçmir. [Uçot](../../platform-store/src/main/java/io/agentplatform/store/Quotas.java) |
| 11. Dayanıqlı icra | Worker dəyişdirilməsi, retry, cancellation, vaxtı keçmiş təsdiq və əvvəlki workflow tarixçəsinin replay-i test edilir. Xarici yazının nəticəsi qeyri-müəyyən qalanda operatorun uzlaşdırması tələb olunur. [Temporal testləri](../../platform-worker/src/test/java/io/agentplatform/worker/AgentRunWorkflowTest.java) |
| 12. Model Gateway | Kataloq provider və model versiyasını sabitləyir. Model parametrləri, axınla cavab, ehtiyat profil, vaxt, tələb olunan imkanlar, məxfilik və məlumat rezidentliyi yoxlanılır. Provider protokolları lokal sınaq serverləri ilə yoxlanılır. [Model adapterləri](../../spring-ai-adapter/) |
| 13. Tool Registry | Java, REST, MCP, agent/workflow hədəfləri və məhdud sənəd axtarışı üçün alət yolları var. Versiyalı müqavilə giriş və çıxış sxemini yoxlayır. Xarici yazı təsdiq və idempotency açarı tələb edir. [İcra](../../platform-worker/src/main/java/io/agentplatform/worker/ToolExecutor.java) |
| 14. MCP | Operator sabit HTTPS ünvanını və uzaqdakı aləti təyin edir. Redirect söndürülür. Sxem pin edildikdə discovery nəticəsi müqayisə edilir. Bearer ilə MCP üçün issuer və audience tələb olunur. Çıxış sahələri deployment allowlist-i ilə məhdudlaşdırıla bilir. [TLS və MCP sınağı](../../platform-connectors/src/test/java/io/agentplatform/connectors/ConnectorTransportTest.java) |
| 15. Workflow compiler | Java builder və deklarativ manifest eyni qrafa çevrilir. Struktur, dövrə, məlum node-lar, versiyalar və delegasiya sərhədləri yoxlanılır. [Ekvivalentlik testi](../../platform-plan/src/test/java/io/agentplatform/plan/BlueprintTest.java) |
| 16. İcra vəziyyəti | Run, addım, status, nəticə və büdcə söhbət tarixçəsindən ayrı saxlanılır. API oxu modeli Temporal ilə uzlaşdırılır. [Reconciliation](../../platform-server/src/main/java/io/agentplatform/server/RunReconciler.java) |
| 17. Yaddaş və fayllar | Cari addımın nəticələri, sessiya mesajları, əvvəlki uyğun icralar və semantik bilik ayrı saxlanılır. Run faylları məzmun heşi ilə dəyişməzdir və həcm limitlidir. İlkin yerləşdirmədə fayl baytları PostgreSQL-də saxlanılır. [Fayl API-si](../../platform-server/src/main/java/io/agentplatform/server/RunArtifactController.java) |
| 18. RAG | Sənəd versiyası, mənbə, təsnifat, hash, embedding revision və rol filtrləri saxlanılır. Yeni indeks hazır olduqdan sonra aktiv versiya dəyişir. Köhnə və girişi ləğv edilmiş mənbələr yoxlanılır. [İndeksləmə](../operator-guide/knowledge-ingestion.md) |
| 19. Təhlükəsizlik | OIDC imza, issuer, audience və identity claim-ləri yoxlanılır. Dərc edilmiş qaydalar və təsdiq qərarları modelin təklifindən ayrıca qiymətləndirilir. İcra və fayl oxunuşu sahiblik və rol yoxlamasından keçir. [OIDC sınaqları](../../platform-server/src/test/java/io/agentplatform/server/OidcApiTest.java) |
| 20. Multi-tenancy | Standart quraşdırma məcburi RLS və səlahiyyətsiz runtime DB rolundan istifadə edir. Adlı workspace ayrıca məlumat bölməsidir. Workspace rolu IdP rolunu genişləndirə bilmir. Ayrı deployment mümkündür. Tenant başına ayrıca PostgreSQL schema provision edən adapter daxil edilməyib. [Workspace modeli](../../platform-store/src/main/java/io/agentplatform/store/WorkspaceDirectory.java) |
| 21. İnsan təsdiqi | Təsdiq tenant, run, addım, konkret arqument heşi, rol, qayda və müddətlə əlaqələndirilir. Köhnəlmiş, rədd edilmiş və ya dəyişmiş sorğu yazı hüququ vermir. [Təsdiqlər](../../platform-store/src/main/java/io/agentplatform/store/Approvals.java) |
| 22. Müşahidə | Dashboard real icra, xərc, son 24 saat üzrə müvəffəqiyyət və P95 müddətini göstərir. API, Temporal və worker poller vəziyyəti ayrıca oxunur. W3C trace konteksti outbox sərhədindən ötürülür. Langfuse aktiv olduqda Studio icranın trace-inə keçid göstərir. [Langfuse inteqrasiyası](../operator-guide/langfuse.md). [Status](../../platform-server/src/main/java/io/agentplatform/server/StatusController.java) |
| 23. Audit | Tenant üzrə axtarış və cursor, hash zənciri, bütövlük yoxlaması və arxiv ixracı var. Zəncirin tətbiqindən əvvəlki qeydlər ayrıca sayılır. Xarici WORM yaddaşının zəmanəti operatorun bucket konfiqurasiyasından asılıdır. [Audit](../../platform-store/src/main/java/io/agentplatform/store/AuditTrail.java) |
| 24. Qiymətləndirmə | Məlumat dəsti və hədəf versiyası, gözlənilən cavab, JSON Schema, alət və arqumentlər, sitat və icra ölçüləri yoxlanılır. Judge nəticəsi ayrıca icranın faktiki giriş və nəticəsinə bağlanır. [Qiymətləndirmə müqaviləsi](../developer-guide/evaluations.md) |
| 25. Verilənlər modeli | Tenant/workspace üzvlükləri, dəyişməz versiyalar, outbox, icralar, siyasətlər, təsdiqlər, sənədlər, nəticələr və audit Liquibase ilə idarə olunur. Bəzi məntiqi obyektlər ortaq versiyalar cədvəlində saxlanılır. [Miqrasiyalar](../../platform-store/src/main/resources/db/changelog/) |
| 26. REST və SSE | OpenAPI agent, workflow və qiymətləndirmə resurslarını ayrı yollar kimi göstərir, təsdiqləmə və dərc əməliyyatlarını fərqləndirir və deployment autentifikasiyasını bildirir. Versiyalı API-də 202 və idempotent start, ləğv, nəticələr, autentifikasiyalı SSE və cursor var. Səlahiyyət və konflikt xətaları Problem Details qaytarır. [API sınağı](../../scripts/smoke.py) |
| 27. Repo | Tək Maven monorepo və ayrıca Studio var. Mənbədə təklif edilən bəzi alt modullar eyni Maven artefaktında paketlər kimi yerləşir. [Reactor](../../pom.xml) |
| 28. Asılılıq qaydaları | SDK müqavilələri Spring AI və Temporal-ın daxili tiplərini tələb etmir. ArchUnit sərhədləri yoxlayır. [Arxitektura testi](../../examples/hello-agent/src/test/java/io/agentplatform/examples/hello/ArchitectureTest.java) |
| 29. Yerləşdirmə | Compose lokal tam sistem üçündür. Helm server, worker və Studio-nu ayrıca yerləşdirir. Klasterdə verilənlər bazası, Temporal namespace, şəbəkə və etibar edilən issuer operator tərəfindən təmin olunur. [Helm təlimatı](../operator-guide/kubernetes.md) |
| 30. Studio | Versiyaların redaktəsi və nəşri, oxuna bilən qraf, Playground, icra, fayllar, bilik, model, alət, təsdiq, siyasət, qiymətləndirmə, audit və üzvlük mövcuddur. Dizaynda drag-and-drop sonrakı mərhələdir. [Studio](../../platform-studio/app/) |
| 31. Qeyri-funksional hədəflər | Lokal gecikmə, tenant ayrılması və worker bərpası ölçülə bilir. 99.9% availability, RPO ≤ 5 dəqiqə və RTO ≤ 60 dəqiqə üçün production müşahidəsi və operatorun bərpa təlimi lazımdır. [Bərpa təlimatı](../operator-guide/kubernetes.md) |
| 32. Test strategiyası | JUnit, Testcontainers, ArchUnit, Temporal replay, real TLS, lokal provider serverləri, Compose API və Playwright testləri istifadə olunur. Aşağıdakı cədvəldə məcburi nasazlıq ssenariləri göstərilib. [Tam yoxlama](../../scripts/verify.sh) |
| 33. ADR | İcra tarixçəsi, təhlükəsizlik, məlumat sahibliyi, xərclər və extension sərhədləri sənədləşdirilib. [ADR siyahısı](../adr/README.md) |
| 34. İnkişaf mərhələləri | SDK-dan Studio-ya qədər funksiyalar repoda işləyir. Real təşkilata inteqrasiya, ictimai yayım və community fəaliyyəti həmin təşkilatın və repozitoriya sahibinin qərarlarını tələb edir. [Roadmap](../../ROADMAP.md) |
| 35. Buraxılış meyarları | SDK, starter, provider, icazəli alətlər, Temporal, versiyalama, ardıcıl/paralel icra, supervisor, təsdiq, persistence, RAG, audit, Compose, nümunələr, təlimatlar və failure testləri üçün kod var. İmzalama və ictimai buraxılış ayrıdır. [Buraxılış qaydası](../operator-guide/release.md) |
| 36. Coding agent qaydaları | Repo Java 21, Maven wrapper, immutable migration, tenant izolyasiyası, Temporal determinismi və dəyişiklik ölçüsünə uyğun testlər tələb edir. [AGENTS.md](../../AGENTS.md) |
| 37. İlk implementasiya tapşırığı | Foundation, starter, fake provider və hello-agent mövcuddur. Nümunə xarici istifadəçinin SDK ilə başlaması üçün ayrıca Maven layihəsi kimi yığılır. [Hello agent](../../examples/hello-agent/) |
| 38. Developer experience | Java agenti üçün ayrıca server və model hesabı tələb olunmur. Quickstart, platform BOM və testkit təqdim olunur. [Developer guide](../developer-guide/java-sdk.md) |
| 39. Ekosistem | Model, Java aləti, bilik və qiymətləndirmə üçün genişləndirmə yolları var. Hazır nümunələr repodadır. Marketplace, bütün vendor adapterləri və community qəbul göstəriciləri ilkin buraxılışın yoxlamasına daxil deyil. [Nümunələr](../../examples/) |
| 40. GitHub təqdimatı | Apache 2.0, NOTICE, contributor və buraxılış sənədləri repodadır. Uzaq repozitoriya adı, trademark seçimi, GitHub topics və ictimai Maven koordinatları release sahibinə aiddir. [Lisenziya](../../LICENSE) |

## Məcburi inteqrasiya ssenariləri

| Hadisə | Yoxlanan nəticə |
| --- | --- |
| Worker icra zamanı dayanır | Əvəzləyici worker eyni Temporal tarixçəsindən davam edir. |
| Provider cavab vermir | Timeout və təsdiqlənmiş fallback siyahısı cəhdləri məhdudlaşdırır. |
| Alətə təkrar çağırış gəlir | PostgreSQL tranzaksiyası daxili təsiri bir dəfə tətbiq edir. Xarici çağırışın qeyri-müəyyən nəticəsi uzlaşdırmaya yönləndirilir. |
| Agent icazəsiz alət istəyir | Worker əməliyyatı rədd edir. |
| Sənəd özünü təlimat kimi təqdim edir | Sənəd model üçün məlumatdır. Worker alət allowlist-ini və siyasəti ayrıca yoxlayır. |
| Təsdiqin vaxtı keçir | Əlaqəli yazı icra olunmur. |
| Büdcə bitir | İcra limit statusu alır və növbəti model cəhdi ayrılmır. |
| İcra vaxtı yeni versiya dərc edilir | Davam edən workflow əvvəl saxladığı child versiyasını istifadə edir. |
| Başqa tenantın sənədi axtarılır | RLS və ACL onu nəticələrdən çıxarır. |
| Worker versiyası yenilənir | Əvvəlki Temporal tarixçəsinin replay testi uyğunluğu yoxlayır. |
| İstifadəçi ləğv edir | Yeni addımlar dayandırılır. Artıq göndərilmiş xarici yazı üçün təsdiqlənmiş nəticə və ya uzlaşdırma qeydi saxlanılır. |

İlkin dizayn Redis, Kafka, OpenSearch, S3, OPA və alternativ vector store-ları seçimli göstərir. Standart yerləşdirmənin işləməsi üçün bunları quraşdırmaq tələb edilmir. Əlavə vendor adapterinin statusu onun real qoşulma və müqavilə testi ilə təsdiqlənməlidir. Production klasteri, şirkətin identity provider-i və ödənişli model endpoint-i ayrıca qəbul mühitləridir.


## Qəbul sərhədləri

- Deployment-installed Java handler üçün standart workspace və mövcud conversation yaddaş müqaviləsi işləyir. Köhnə Java model çağırışı checkpoint-lərinin yeni formatla replay uyğunluğu ayrıca qəbul tələb edir. Uyğun olmayan xüsusi yaddaş parametrləri publication mərhələsində rədd edilir. Named workspace-də deklarativ agent və workflow dəstəklənir.
- Platformanın Compose E2E sınağı, HTTPS/MCP nəqliyyat testi və Temporal xarici təsir testi ayrı mühitlərdə işləyir. Bu üç komponenti vahid xarici yazı ssenarisində birləşdirən yoxlama açıq qalır.
- Arxiv üçün S3 Object Lock adapterinin müqavilə testi var. Canlı bucket, real kommersiya model hesabı, production IdP və Kubernetes klasterində yerləşdirmə bu lokal qəbulun hüdudundan kənardadır. Xarici buraxılış üçün imza və Trivy yoxlaması operatorun açarını və cari təhlükəsizlik bazasını tələb edir.

## Son yoxlamanın nəticəsi

25 noyabr 2026-cı ildə ./scripts/verify.sh lokal mühitdə uğurla tamamlandı.

| Yoxlama | Nəticə |
| --- | --- |
| Java modulları və inteqrasiya testləri | 112 test keçdi, buraxılmış test olmadı. |
| Arxiv alətləri | 2 Python testi keçdi. |
| İşlək platformanın HTTP API-si | 19 ssenari qrupu keçdi. Tenant və workspace izolyasiyası, versiyanın sabit qalması və təkrar sorğuların davranışı da yoxlanıldı. |
| Studio | 3 Playwright testi keçdi. Agentin dərc edilməsi və icrası, mobil təsdiq axını, workspace idarəetməsi və audit yoxlanıldı. |
| SSO | Lokal OIDC issuer ilə brauzerdə PKCE giriş axını keçdi. API imzalanmış tokeni yoxladı. |

Ayrıca lokal ölçmədə icra siyahısına 4 paralel sorğu ilə cəmi 80 sorğu göndərildi. Median cavab müddəti 66,8 ms, p95 isə 81,9 ms oldu. Bu ölçmə lokal verilənlər və həmin paralellik səviyyəsi üçün keçərlidir.
