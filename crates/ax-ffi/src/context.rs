//! Contexte natif et registre de session (C-10, C-14).
//!
//! L'ABI désigne le contexte par un `u64` et jamais par un pointeur : R-264
//! interdit de renvoyer un pointeur brut, et un entier ne permet pas à un
//! appelant fautif de déréférencer quoi que ce soit. Le jeton porte un motif
//! reconnaissable et un numéro de session, si bien qu'un jeton forgé, ou celui
//! d'une session déjà fermée, est rejeté au lieu de désigner le contexte
//! courant.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, MutexGuard, PoisonError};

use ax_core::{BufferPool, ContextGuard, RuntimeState};

/// Motif porté par les bits de poids fort d'un jeton de contexte.
///
/// « AXIO » en ASCII : un jeton nul, un pointeur recyclé ou un entier arbitraire
/// ne le portent pas, et sont rejetés avant toute autre vérification.
const TOKEN_TAG: u64 = 0x4158_494F_0000_0000;

/// Numéro de la prochaine session.
///
/// Il avance à chaque initialisation, y compris après un arrêt propre : le
/// jeton d'une session fermée ne peut donc jamais désigner la suivante.
static NEXT_SESSION: AtomicU64 = AtomicU64::new(1);

/// La session vivante, s'il y en a une.
static SESSION: Mutex<Option<Session>> = Mutex::new(None);

/// État interne d'une session native.
#[derive(Debug)]
pub struct Session {
    token: u64,
    state: RuntimeState,
    last_error: Option<String>,
    panics: u64,
    buffers: BufferPool,
    /// Détenu pour la durée de la session : c'est lui qui garantit l'unicité du
    /// contexte dans le processus (R-450).
    _guard: ContextGuard,
}

impl Session {
    /// État courant du runtime.
    #[must_use]
    pub fn state(&self) -> RuntimeState {
        self.state
    }

    /// Nombre de panics capturées depuis le démarrage.
    ///
    /// Alimente la métrique `axion.native.panics` (R-310).
    #[must_use]
    pub fn panics(&self) -> u64 {
        self.panics
    }

    /// Dernier message d'erreur, s'il y en a un.
    #[must_use]
    pub fn last_error(&self) -> Option<&str> {
        self.last_error.as_deref()
    }

    /// Enregistre un message d'erreur, qui remplace le précédent.
    pub fn set_last_error(&mut self, message: impl Into<String>) {
        self.last_error = Some(message.into());
    }

    /// Tampons de transfert de la session (IF-02).
    #[must_use]
    pub fn buffers(&mut self) -> &mut BufferPool {
        &mut self.buffers
    }

    /// Enregistre une panic capturée et empoisonne le contexte.
    ///
    /// R-310 : la panic est comptée, et le contexte passe en `POISONED` dès
    /// lors que l'invariant interne n'est pas restaurable. Une panic traversant
    /// un point d'entrée signifie précisément qu'on ne sait pas dans quel état
    /// le travail s'est interrompu : le cas restaurable est l'exception, pas la
    /// règle, et le supposer serait le contraire de l'option conservatrice.
    pub fn record_panic(&mut self, message: impl Into<String>) {
        self.panics += 1;
        self.state = RuntimeState::Poisoned;
        self.last_error = Some(message.into());
    }
}

/// Empoisonnement du verrou de session.
///
/// Un `Mutex` empoisonné signale qu'un thread a paniqué en le tenant. On
/// récupère l'accès plutôt que de propager : la session porte déjà son propre
/// état `POISONED`, et refuser l'accès ici empêcherait `axion_shutdown` de
/// libérer quoi que ce soit — alors que R-311 exige qu'il reste possible.
fn recover<T>(result: Result<T, PoisonError<T>>) -> T {
    match result {
        Ok(value) => value,
        Err(poisoned) => poisoned.into_inner(),
    }
}

fn sessions() -> MutexGuard<'static, Option<Session>> {
    recover(SESSION.lock())
}

/// Ouvre une session et renvoie son jeton.
///
/// # Erreurs
///
/// Renvoie le code `E-1004` si une session est déjà ouverte dans ce processus
/// (R-450).
pub fn open() -> Result<u64, i32> {
    let mut slot = sessions();
    if slot.is_some() {
        return Err(ax_core::CoreError::AlreadyInitialized.code());
    }
    // Le jeton d'unicité est pris avant toute autre chose : si un autre
    // composant détient déjà le contexte, l'ouverture échoue ici.
    let guard = ContextGuard::acquire().map_err(|error| error.code())?;

    let session = NEXT_SESSION.fetch_add(1, Ordering::Relaxed);
    let token = TOKEN_TAG | session;
    *slot = Some(Session {
        token,
        state: RuntimeState::Ready,
        last_error: None,
        panics: 0,
        buffers: BufferPool::new(),
        _guard: guard,
    });
    Ok(token)
}

/// Ferme la session désignée.
///
/// # Erreurs
///
/// `E-2001` si le jeton ne désigne pas la session vivante.
pub fn close(token: u64) -> Result<(), i32> {
    let mut slot = sessions();
    match slot.as_ref() {
        Some(session) if session.token == token => {
            *slot = None;
            Ok(())
        }
        _ => Err(ax_core::CoreError::InvalidHandle.code()),
    }
}

/// Indique si une session est ouverte.
#[must_use]
pub fn is_open() -> bool {
    sessions().is_some()
}

/// Exécute `action` sur la session désignée, si le jeton est valide.
///
/// `allow_poisoned` autorise l'accès à un contexte `POISONED`, que R-311
/// réserve à `axion_shutdown` et `axion_last_error`.
///
/// # Erreurs
///
/// `E-2001` si le jeton est inconnu, ou si le contexte est empoisonné et que
/// l'appel n'en fait pas partie.
pub fn with<R>(
    token: u64,
    allow_poisoned: bool,
    action: impl FnOnce(&mut Session) -> R,
) -> Result<R, i32> {
    let mut slot = sessions();
    let session = match slot.as_mut() {
        Some(session) if session.token == token => session,
        _ => return Err(ax_core::CoreError::InvalidHandle.code()),
    };
    if !allow_poisoned && !session.state.accepts_calls() {
        return Err(ax_core::CoreError::InvalidHandle.code());
    }
    Ok(action(session))
}

/// Réinitialise l'état global.
///
/// Réservé aux tests : les points d'entrée d'une même bibliothèque partagent le
/// registre, et un test qui ouvre une session doit pouvoir la refermer même
/// après un échec.
#[cfg(test)]
pub(crate) fn reset_for_tests() {
    *sessions() = None;
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Le registre est un état global au processus : les tests qui l'ouvrent
    /// et le ferment ne peuvent pas s'exécuter en parallèle sans se marcher
    /// dessus. Un seul test les enchaîne donc, dans un ordre maîtrisé.
    #[test]
    fn cycle_de_vie_d_une_session() {
        reset_for_tests();
        assert!(!is_open());

        let token = open().expect("première ouverture refusée");
        assert!(is_open());
        // Le jeton porte le motif : ni zéro, ni un petit entier.
        assert_eq!(token & 0xFFFF_FFFF_0000_0000, TOKEN_TAG);
        assert_ne!(token, 0);

        // R-450 : une seconde ouverture est refusée avec E-1004.
        assert_eq!(open().unwrap_err(), -1004);

        // Un jeton forgé ne désigne rien.
        for forge in [0, 1, token ^ 1, TOKEN_TAG] {
            assert_eq!(
                with(forge, false, |_| ()).unwrap_err(),
                -2001,
                "jeton {forge:#x} accepté à tort"
            );
        }

        with(token, false, |session| {
            assert_eq!(session.state(), RuntimeState::Ready);
            session.set_last_error("essai");
        })
        .unwrap();
        with(token, false, |session| {
            assert_eq!(session.last_error(), Some("essai"));
        })
        .unwrap();

        // R-311 : un contexte empoisonné refuse les appels ordinaires, mais
        // reste joignable pour l'arrêt et la lecture d'erreur.
        with(token, false, |session| {
            session.record_panic("panic simulée")
        })
        .unwrap();
        assert_eq!(with(token, false, |_| ()).unwrap_err(), -2001);
        with(token, true, |session| {
            assert_eq!(session.state(), RuntimeState::Poisoned);
            assert_eq!(session.panics(), 1);
            assert_eq!(session.last_error(), Some("panic simulée"));
        })
        .unwrap();

        // La fermeture n'accepte que le bon jeton.
        assert_eq!(close(token ^ 0xFF).unwrap_err(), -2001);
        close(token).expect("fermeture refusée");
        assert!(!is_open());

        // Le jeton d'une session fermée ne désigne plus rien, et la session
        // suivante en reçoit un différent.
        assert_eq!(with(token, true, |_| ()).unwrap_err(), -2001);
        let next = open().expect("réouverture refusée");
        assert_ne!(next, token, "jeton réutilisé d'une session à l'autre");
        close(next).unwrap();
    }
}
