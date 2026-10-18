# Fiido Open — BLE Explorer (MVP)

Appli Android open source d'interopérabilité pour vélo électrique **Fiido C11**,
dans le cadre de l'art. L122-6-1 CPI / directive 2009/24/CE.

**But du projet** : restaurer la conformité française (désactivation de la gâchette /
limite 6 km/h), afficher la batterie exacte, exporter vers Health Connect, et
remplacer la géolocalisation du vélo garé.

## Étape actuelle — MVP « Explorer »
Se connecte au vélo en BLE, s'abonne à toutes les caractéristiques *notify*,
journalise chaque trame en hexadécimal et permet d'envoyer des trames de test.
Objectif : reconstituer le protocole à partir de trames réelles.

> Ne débride pas le vélo au-delà de 25 km/h, ne modifie pas le firmware du
> contrôleur, ne contourne aucun chiffrement.

## Téléchargement de l'APK
Onglet **Releases** → `latest` → fichier `.apk`.
(Compilée automatiquement par GitHub Actions à chaque mise à jour.)
