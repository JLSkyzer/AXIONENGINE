//! Temps CPU du thread appelant, en nanosecondes (C-15, R-500).
//!
//! Le chronomètre du pas de simulation mesure le temps **mural** : il compte aussi le temps
//! où le thread attendait sans calculer — préempté par le reste du jeu, par exemple. Le
//! temps CPU du thread ne compte que ce qu'il a calculé. Un pas lent dont le temps CPU est
//! court n'a pas travaillé : il a attendu.
//!
//! - **Unix** : `clock_gettime(CLOCK_THREAD_CPUTIME_ID)`, à la nanoseconde sous Linux, à la
//!   microseconde près sous macOS.
//! - **Windows** : `QueryThreadCycleTime` compte les cycles d'horloge consommés par le
//!   thread. `GetThreadTimes`, en temps, n'avance qu'aux interruptions d'horloge (15,6 ms)
//!   et ne peut rien dire d'un pas de quelques millisecondes. Les cycles sont convertis par
//!   une fréquence calibrée une fois, contre `Instant`, sur trois attentes actives de 2 ms,
//!   dont on garde la plus haute : une mesure préemptée ne peut que la sous-estimer. Le
//!   résultat est une estimation, juste sur un processeur à compteur de cycles invariant —
//!   le cas de tout processeur x86-64 récent. La calibration a lieu au premier appel : il se
//!   fait à l'ouverture de la session, hors de toute mesure.
//! - **Ailleurs** : `None`.

// Les appels système de cette horloge sont la seule raison d'être de ce module (R-2120 :
// `unsafe` confiné à la frontière native).
#![allow(unsafe_code)]

/// Temps CPU consommé par le thread appelant depuis son démarrage, en nanosecondes, ou
/// `None` si la plateforme ne le fournit pas.
#[must_use]
pub fn thread_cpu_ns() -> Option<u64> {
    imp::thread_cpu_ns()
}

#[cfg(unix)]
mod imp {
    pub(super) fn thread_cpu_ns() -> Option<u64> {
        let mut spec = libc::timespec {
            tv_sec: 0,
            tv_nsec: 0,
        };
        // SAFETY: `spec` est une `timespec` valide, possédée et modifiable, que
        // `clock_gettime` ne fait qu'écrire ; l'horloge demandée existe sous Linux et macOS.
        let code = unsafe { libc::clock_gettime(libc::CLOCK_THREAD_CPUTIME_ID, &raw mut spec) };
        if code != 0 {
            return None;
        }
        let seconds = u64::try_from(spec.tv_sec).ok()?;
        let nanos = u64::try_from(spec.tv_nsec).ok()?;
        seconds.checked_mul(1_000_000_000)?.checked_add(nanos)
    }
}

#[cfg(windows)]
mod imp {
    use std::sync::OnceLock;
    use std::time::{Duration, Instant};
    use windows_sys::Win32::System::Threading::GetCurrentThread;
    use windows_sys::Win32::System::WindowsProgramming::QueryThreadCycleTime;

    /// Cycles consommés par le thread appelant.
    fn cycles() -> Option<u64> {
        let mut cycles = 0u64;
        // SAFETY: `GetCurrentThread` rend un pseudo-handle toujours valide pour le thread
        // appelant, sans rien à libérer ; `cycles` est un `u64` possédé et modifiable, que
        // l'appel ne fait qu'écrire.
        let ok = unsafe { QueryThreadCycleTime(GetCurrentThread(), &raw mut cycles) };
        (ok != 0).then_some(cycles)
    }

    /// Cycles par nanoseconde, calibrés une fois contre `Instant` (voir la doc du module).
    fn cycles_per_ns() -> Option<f64> {
        static CALIBRATION: OnceLock<Option<f64>> = OnceLock::new();
        *CALIBRATION.get_or_init(|| {
            let mut best: Option<f64> = None;
            for _ in 0..3 {
                let start = cycles()?;
                let started = Instant::now();
                while started.elapsed() < Duration::from_millis(2) {
                    std::hint::spin_loop();
                }
                let elapsed = started.elapsed().as_nanos() as f64;
                let spent = cycles()?.saturating_sub(start) as f64;
                if elapsed > 0.0 && spent > 0.0 {
                    let rate = spent / elapsed;
                    best = Some(best.map_or(rate, |previous: f64| previous.max(rate)));
                }
            }
            best
        })
    }

    pub(super) fn thread_cpu_ns() -> Option<u64> {
        // La calibration d'abord : ses attentes actives précèdent la lecture des cycles et
        // n'entrent donc dans aucune mesure.
        let rate = cycles_per_ns()?;
        let nanos = cycles()? as f64 / rate;
        // Un taux calibré est fini et strictement positif : le quotient est fini, et `as`
        // ramène un éventuel dépassement à la borne.
        Some(nanos as u64)
    }
}

#[cfg(not(any(unix, windows)))]
mod imp {
    pub(super) fn thread_cpu_ns() -> Option<u64> {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::thread_cpu_ns;
    use std::time::{Duration, Instant};

    /// Fait calculer le thread pendant `duree`, sans jamais le laisser dormir.
    fn calcule(duree: Duration) -> u64 {
        let started = Instant::now();
        let mut acc = 0u64;
        while started.elapsed() < duree {
            acc = std::hint::black_box(acc.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1));
        }
        acc
    }

    #[test]
    fn le_temps_cpu_avance_quand_le_thread_calcule() {
        let avant = thread_cpu_ns().expect("horloge CPU de thread sur cette plateforme");
        calcule(Duration::from_millis(30));
        let apres = thread_cpu_ns().expect("horloge CPU de thread");
        let ecoule = apres.saturating_sub(avant);
        // 30 ms de calcul : le thread a pu être préempté par moments, pas la plupart du
        // temps ; et il n'a pas pu calculer plus longtemps qu'il n'a duré, à la marge de
        // l'estimation près.
        assert!(
            ecoule >= 10_000_000,
            "{ecoule} ns de CPU pour 30 ms de calcul"
        );
        assert!(
            ecoule <= 60_000_000,
            "{ecoule} ns de CPU pour 30 ms de calcul"
        );
    }

    #[test]
    fn le_temps_cpu_n_avance_pas_quand_le_thread_dort() {
        // C'est ce qui distingue cette horloge du temps mural : une attente ne compte pas.
        let avant = thread_cpu_ns().expect("horloge CPU de thread");
        std::thread::sleep(Duration::from_millis(40));
        let apres = thread_cpu_ns().expect("horloge CPU de thread");
        let ecoule = apres.saturating_sub(avant);
        assert!(
            ecoule < 10_000_000,
            "{ecoule} ns de CPU pour 40 ms de sommeil"
        );
    }
}
