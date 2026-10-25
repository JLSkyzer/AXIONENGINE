#!/usr/bin/env bash
# Lance un banc de rendu (ADR-126) sous Xvfb : `tools/ci/banc_de_rendu.sh runRenderTest`.
#
# La toute première ouverture d'un contexte llvmpipe sur un runner froid dépasse parfois le minuteur
# fixe de 10 s de la fenêtre anticipée de Forge, avant tout code d'AXION (runs 40139908450 et
# 40146214298 ; 4 à 5 s d'habitude, presque rien aux lancements suivants). Seul cet échec-là se rejoue,
# une fois : un banc rouge pour toute autre raison reste rouge.
set -u
tache="$1"
sortie="banc-${tache}.log"

lancer() {
    xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew "$tache" --no-daemon --stacktrace 2>&1 | tee "$sortie"
    return "${PIPESTATUS[0]}"
}

if lancer; then
    exit 0
fi
if grep -q "Timed out trying to setup the Game Window" "$sortie"; then
    echo "AXION : fenêtre anticipée de Forge expirée avant tout code d'AXION ; second lancement"
    lancer
    exit $?
fi
exit 1
