# Handleiding

Voor beheerders en procesontwerpers die GitHub vanuit een proces willen bedienen. Je hebt
hiervoor geen programmeerkennis nodig: alles in deze handleiding gebeurt in de
beheerinterface en in de procesmodelleur. Kennis van GitHub zelf — issues, pull requests,
branches — wordt wel verondersteld.

Zoek je de precieze naam of het type van een instelling, of wat een actie exact teruggeeft,
dan staat dat in de [pluginreferentie](plugin.md).

## Wat deze plugin doet

De plugin laat een proces met GitHub praten: issues en pull requests lezen en aanmaken,
reviews afhandelen, CI-checks volgen, bestanden en branches beheren en projectborden
bijwerken. In totaal 33 acties, plus twee vrije acties voor alles wat daar niet in past.

Daarmee kun je werk dat nu in scripts of met de hand gebeurt als proces modelleren: een
ticket oppakken, een branch maken, een pull request openen, de checks afwachten, de
reviewopmerkingen beantwoorden en mergen — met de volgorde en de beslissingen in het
procesdiagram in plaats van in een script.

Twee dingen die de plugin **niet** doet:

- **Hij kijkt niet zelf.** Er wordt niets gepolld en er komt niets uit zichzelf binnen. Wil
  je dat een proces elke tien minuten de openstaande issues bekijkt, dan zeg je dat met een
  timer in het procesdiagram, die vervolgens de actie *Issues opsommen* aanroept.
- **Hij ontvangt geen webhooks.** GitHub kan dit proces niet aanstoten; het proces vraagt
  altijd zelf.

## Voordat je begint

| Wat je nodig hebt | Wie levert dat meestal |
| --- | --- |
| Een GitHub-token met precies de rechten die de processen nodig hebben | de beheerder van de GitHub-organisatie |
| De naam van de repository, in de vorm `eigenaar/repository` | de ontwikkelaars van het project |
| Bij GitHub Enterprise Server: het adres van de eigen GitHub-server | de beheerder van die server |

## Stap 1 — De verbinding instellen

Ga in de beheerinterface naar **Plugins** en kies **Plugin configureren**. Kies bij
*Kies je plugin* de tegel **GitHub**. Je krijgt dan een formulier met de onderstaande velden.
Bewaar met **Configuratie opslaan**.

| Veld | Wat je invult |
| --- | --- |
| **Configuratienaam** | Een naam die jij herkent, bijvoorbeeld `GitHub – leesrechten`. Deze naam kies je later bij het koppelen aan een processtap. |
| **GitHub API-URL** | `https://api.github.com` voor github.com. Voor een eigen GitHub Enterprise Server is dit `https://jouw-host/api/v3`. |
| **Token** | Het GitHub-token. Lees eerst [Over het token](#over-het-token) hieronder. |
| **Standaard repository** | Optioneel: `eigenaar/repository`. Elke actie die zelf geen repository invult, werkt op deze. Laat leeg als de processen over meerdere repositories werken. |
| **GraphQL-URL** | Laat leeg. De plugin leidt hem af uit de API-URL, en dat klopt voor zowel github.com als Enterprise Server. |
| **Items per pagina** | Laat leeg. Bepaalt hoeveel items er per aanvraag bij GitHub worden opgehaald; 100 is het maximum dat GitHub toestaat. |
| **Maximum aantal pagina's per actie** | Hoeveel pagina's één lijstactie leest voordat hij stopt. Standaard 5, dus maximaal zo'n 500 items. Zie [Lijsten en onvolledige lijsten](#lijsten-en-onvolledige-lijsten). |

### Over het token

**Alles wat dit token mag, mag elk proces dat aan deze configuratie gekoppeld is.** De acties
controleren dat niet, en de vrije REST- en GraphQL-actie bereiken alles wat het token
bereikt. Geef een configuratie die alleen hoeft te lezen dus ook een token dat alleen kan
lezen.

Moeten sommige processen lezen en andere schrijven, maak dan **twee configuraties** met twee
tokens in plaats van één token dat alles mag. In de procesmodelleur kiest elke stap zelf
welke configuratie hij gebruikt, dus dat kost verder niets.

Let ook op **wie** het token is: GitHub staat niet toe dat een account zijn eigen pull request
goedkeurt. Laat je een proces pull requests aanmaken én reviewen, dan zijn dat twee accounts.
De actie *Ingelogde gebruiker ophalen* vertelt je wie er achter een token zit.

## Stap 2 — Een actie aan een processtap koppelen

Open het proces in de procesmodelleur en zet een **servicetaak** (service task) neer. Klik de
taak aan, kies **Proceskoppeling aanmaken**, kies de configuratie uit stap 1 en daarna de
actie die je wilt uitvoeren.

Elke actie heeft twee velden die altijd terugkomen:

| Veld | Wat je invult |
| --- | --- |
| **Repository** | Laat leeg om de standaard repository van de configuratie te gebruiken. Accepteert ook een procesvariabele, bijvoorbeeld `pv:targetRepository`, zodat één proces meerdere repositories kan bedienen. |
| **Resultaatvariabele** | De naam van de procesvariabele waarin het antwoord terechtkomt. Laat leeg om het antwoord niet te bewaren. |

Een lege resultaatvariabele is een prima keuze bij een actie waar het om de handeling gaat en
niet om het antwoord — een geplaatste reactie, een toegekend label. Bij een actie die iets
ophaalt, vul je hem uiteraard wel in; anders heeft de stap niets opgeleverd.

De overige velden verschillen per actie en hebben allemaal een toelichting in het formulier
zelf.

## Welke acties er zijn

| Groep | Acties |
| --- | --- |
| **Repository en gebruiker** | Repository ophalen · Repositories opsommen · Ingelogde gebruiker ophalen |
| **Issues** | Issues opsommen · Issue ophalen · Issues en pull requests zoeken · Issue aanmaken · Issue bijwerken · Reageren op issue of pull request |
| **Pull requests** | Pull requests opsommen · Pull request ophalen · Pull request aanmaken · Pull request bijwerken · Pull request klaarzetten of terug naar concept · Pull request mergen |
| **Review** | Pull request reviewen · Reviewers vragen · Reviewthreads ophalen · Antwoorden op reviewreactie · Reviewthread oplossen |
| **Labels** | Label aanmaken |
| **CI** | Checkstatus ophalen · Workflow-runs opsommen · Workflow-run ophalen · Joblogboek ophalen · Workflow opnieuw uitvoeren |
| **Bestanden en branches** | Bestandsinhoud ophalen · Bestand aanmaken of bijwerken · Branch aanmaken |
| **Projectborden** | Projectitems ophalen · Projectveld instellen |
| **Al het overige** | REST-aanvraag · GraphQL-query |

De laatste twee zijn een achterdeur: die sturen je aanvraag ongewijzigd naar GitHub door en
bewaren het antwoord zoals het binnenkomt. Ze bestaan omdat GitHub meer kan dan in deze lijst
past. Kun je iets doen met een gewone actie, doe dat dan — die geeft een veel beter leesbaar
antwoord terug.

## Het antwoord gebruiken

Een actie die één ding ophaalt — een issue, een repository — zet de gegevens daarvan
rechtstreeks in de resultaatvariabele.

Een actie die een lijst ophaalt, geeft altijd drie dingen terug: de **items** zelf, het
**aantal**, en of de lijst **afgekapt** is.

De antwoorden worden onderweg opgeschoond: alleen de velden waar een proces iets aan heeft,
en steeds onder dezelfde naam. Eén pull request is bij GitHub al gauw vijftien kilobyte aan
gegevens, en een lijst van vijftig daarvan is een procesvariabele waar niemand meer doorheen
komt. Welke velden je precies terugkrijgt, staat per actie in de
[pluginreferentie](plugin.md).

### Lijsten en onvolledige lijsten

Een lijstactie stopt na het aantal pagina's dat bij **Maximum aantal pagina's per actie**
staat, en meldt dan dat de lijst is afgekapt.

**Laat het proces daar altijd op controleren** voordat het concludeert dat er niets meer te
doen is. Een korte lijst en een afgekapte lijst zien er verder precies hetzelfde uit, en een
proces dat vertakt op "geen openstaande pull requests meer" kiest bij een opgeraakt
paginabudget de verkeerde tak.

## Waar je op moet letten

Een handvol dingen die bij het inrichten het vaakst misgaan:

| Onderwerp | Waar je op moet letten |
| --- | --- |
| **Issues opsommen** | Geeft óók pull requests terug. GitHub behandelt die intern als issues. Elk item vertelt zelf of het een pull request is; wil je alleen echte issues, laat het proces daar dan op filteren. |
| **Labels en behandelaars** | Je geeft op wat erbíj moet en wat eraf moet — niet de complete lijst. Dat is met opzet: anders gooi je weg wat een collega er intussen op gezet heeft. Een label weghalen dat er niet op zit, is geen fout. |
| **Doelbranch bij een pull request** | Laat leeg. De plugin gebruikt dan de standaardbranch van de repository zelf, en dat is lang niet overal `main`. |
| **Mergen** | Vul de verwachte commit in die je eerder bij het ophalen van de pull request hebt gekregen. GitHub weigert de merge dan als er intussen nog iets is bijgekomen, in plaats van iets te mergen wat niemand gereviewd heeft. |
| **Zoeken** | Zoeken gaat via de zoekindex van GitHub, niet via de repository zelf. Die index loopt seconden tot minuten achter, dus vlak na een wijziging zoeken kan een verouderd beeld geven. |
| **Checkstatus** | De status is `none` als er nog helemaal niets over die commit gerapporteerd is. Dat is nadrukkelijk niet hetzelfde als geslaagd: laat een proces nooit mergen op `none`. |
| **Label aanmaken** | Een label dat al bestaat levert geen fout op. Je kunt deze stap dus gewoon elke keer uitvoeren, zonder omweg voor het geval hij er al is. |
| **Bestand aanmaken of bijwerken** | Schrijft rechtstreeks naar de branch, zonder pull request ertussen. Hetzelfde pad twee keer schrijven overschrijft de vorige versie. |

## Wat er mis kan gaan

Weigert GitHub een aanvraag, dan mislukt de processtap en blijft hij als incident staan. De
melding bevat de reden die GitHub zelf geeft, inclusief het veld waar het om ging. Los de
oorzaak op en voer de taak opnieuw uit.

De meest voorkomende oorzaken:

| Wat je ziet | Waarschijnlijke oorzaak | Wat je doet |
| --- | --- | --- |
| Elke actie mislukt meteen | Verkeerd token, verlopen token, of de verkeerde API-URL | Controleer het token en de URL in de configuratie |
| Lezen werkt, schrijven niet | Het token mist het recht om te schrijven | Vraag een token met de juiste rechten, of gebruik een tweede configuratie |
| Een repository "bestaat niet" | Een privérepository waar dit token niet bij mag — GitHub zegt dan niet "geen toegang" maar "niet gevonden" | Controleer of het token toegang tot déze repository heeft |
| Een pull request goedkeuren mislukt | Het token hoort bij het account dat de pull request zelf heeft aangemaakt | Gebruik een tweede account voor de review |
| Een lijst lijkt onvolledig | Het paginabudget is opgeraakt | Verhoog **Maximum aantal pagina's per actie**, of filter de aanvraag scherper |

## Meer lezen

- [Pluginreferentie](plugin.md) — alle acties, hun instellingen en wat ze teruggeven
- [Aan de slag](getting-started.md) — de sandbox met voorbeeldprocessen draaien
- [Release-notities](release-notes.md) — wat er per versie is veranderd
