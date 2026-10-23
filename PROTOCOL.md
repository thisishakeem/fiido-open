# Protocole BLE Fiido C11 (reconstitué)

Reconstitué par analyse statique de l'APK + capture HCI réelle (bug report Android).

## Transport
- Service série `00010203-0405-0607-0809-0a0b0c0dffe0`
  - `ffe2` (write / write-no-response) : commandes téléphone → vélo
  - `ffe1` (notify) : réponses vélo → téléphone
- Le vélo est **muet** tant qu'on ne l'interroge pas (polling).

## Format de trame
`46 64 <op> <reg> [data...] <xor>`
- En-tête fixe `46 64` ("Fd")
- `op` : `0x55` = lecture, `0xAA` = réponse (vélo) / écriture (avec `01` = longueur)
- `xor` = XOR de **tous** les octets précédents (checksum 1 octet)
- NB : une CRC-16/CCITT (poly 0x1021, init 0x0514) existe aussi dans le code mais
  sert au DFU, pas au protocole courant.

## Lectures (téléphone → ffe2) — exactes, capturées
- `46 64 55 26 1C 4D` → état principal (batterie/vitesse/assistance)
- `46 64 55 0C C8 B3` → compteur/état
- `46 64 55 0D 60 1A` / `46 64 55 0D 7B 01` → versions/infos
- `46 64 55 1C 00 6B` → table des vitesses
- `46 64 55 04 53 20` → état

## Écriture (téléphone → ffe2)
`46 64 AA 01 <reg> <valeur> <xor>`
- Registre `0x26` = niveau d'assistance (valeur 0..5) — **confirmé** par capture
- Registre **gâchette** : à identifier (absent de l'appli récente)

## Réponse d'état (vélo → ffe1), registre 0x26, ex. :
`46 64 AA 26 1C 00 01 26 EC 00 1A 00 00 0F 50 05 80 00 00 00 00 E0 64 7B 02 48 1E 0F 00 00 00 00 00 00 00 00 00 19 04 01 75 00 90 E4`
Offsets batterie/vitesse en cours de calage contre l'appli officielle.

## Registre 0x27 — Alimentation (confirmé sur vélo, 2026-10-23)
- `46 64 AA 01 27 80 <xor>` → allume le vélo (power ON)
- `46 64 AA 01 27 08 <xor>` → éteint le vélo (power OFF)
- 0x2B (valeurs 0x40 / 0x00) : aucun effet observable — klaxon/lumière/gardien sont ailleurs (à cartographier par scan-diff ou capture de l'appli officielle).
