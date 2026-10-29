//! Modèles animés écrits par code (ADR-155 D8) : exacts, reproductibles, sans outil externe.
//!
//! Chaque modèle est un `.gltf` autonome, ses données dans un `data:` URI. Les valeurs sont
//! choisies pour qu'un test sache ce qu'il doit lire : un bras à deux os qui se plie d'un quart
//! de tour, un pendule de node en palier et en linéaire, un rebond en `CUBIC_SPLINE`.

#![allow(dead_code)]

/// Préfixe d'un URI de données binaires.
const DATA_PREFIX: &str = "data:application/octet-stream;base64,";

const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/// `FLOAT`, `UNSIGNED_BYTE`, `UNSIGNED_SHORT` de glTF.
const FLOAT: u32 = 5126;
const UNSIGNED_BYTE: u32 = 5121;
const UNSIGNED_SHORT: u32 = 5123;

/// Le sinus et le cosinus d'un huitième de tour : le quaternion d'un quart de tour.
pub const DEMI_RACINE: f32 = core::f32::consts::FRAC_1_SQRT_2;

/// Un document en construction : un tampon, ses vues, ses accesseurs.
#[derive(Default)]
pub struct Modele {
    bin: Vec<u8>,
    vues: Vec<String>,
    accesseurs: Vec<String>,
}

impl Modele {
    fn ajoute(
        &mut self,
        octets: &[u8],
        composant: u32,
        genre: &str,
        nombre: usize,
        bornes: &str,
    ) -> usize {
        // Chaque accesseur commence sur un multiple de quatre, comme glTF l'exige des flottants.
        while !self.bin.len().is_multiple_of(4) {
            self.bin.push(0);
        }
        let debut = self.bin.len();
        self.bin.extend_from_slice(octets);
        self.vues.push(format!(
            "{{ \"buffer\": 0, \"byteOffset\": {debut}, \"byteLength\": {} }}",
            octets.len()
        ));
        self.accesseurs.push(format!(
            "{{ \"bufferView\": {}, \"componentType\": {composant}, \"count\": {nombre}, \
             \"type\": \"{genre}\"{bornes} }}",
            self.vues.len() - 1
        ));
        self.accesseurs.len() - 1
    }

    /// Des flottants de genre `SCALAR`, `VEC3`, `VEC4` ou `MAT4`, bornes comprises.
    pub fn flottants(&mut self, valeurs: &[f32], genre: &str) -> usize {
        let composants = match genre {
            "SCALAR" => 1,
            "VEC3" => 3,
            "VEC4" => 4,
            "MAT4" => 16,
            autre => panic!("genre {autre}"),
        };
        let mut min = vec![f32::INFINITY; composants];
        let mut max = vec![f32::NEG_INFINITY; composants];
        for element in valeurs.chunks_exact(composants) {
            for (rang, valeur) in element.iter().enumerate() {
                min[rang] = min[rang].min(*valeur);
                max[rang] = max[rang].max(*valeur);
            }
        }
        let liste = |valeurs: &[f32]| {
            valeurs
                .iter()
                .map(|valeur| format!("{valeur:?}"))
                .collect::<Vec<_>>()
                .join(", ")
        };
        let bornes = format!(", \"min\": [{}], \"max\": [{}]", liste(&min), liste(&max));
        let octets: Vec<u8> = valeurs
            .iter()
            .flat_map(|valeur| valeur.to_le_bytes())
            .collect();
        self.ajoute(&octets, FLOAT, genre, valeurs.len() / composants, &bornes)
    }

    /// Des `VEC4` d'octets non signés : les joints des sommets.
    pub fn joints(&mut self, valeurs: &[[u8; 4]]) -> usize {
        let octets: Vec<u8> = valeurs.iter().flatten().copied().collect();
        self.ajoute(&octets, UNSIGNED_BYTE, "VEC4", valeurs.len(), "")
    }

    /// Des indices de triangles, `u16`.
    pub fn indices(&mut self, valeurs: &[u16]) -> usize {
        let octets: Vec<u8> = valeurs
            .iter()
            .flat_map(|valeur| valeur.to_le_bytes())
            .collect();
        self.ajoute(&octets, UNSIGNED_SHORT, "SCALAR", valeurs.len(), "")
    }

    /// Le document : `corps` porte la scène, les nodes, les meshes, les skins et les animations.
    pub fn document(&self, corps: &str) -> String {
        format!(
            "{{\n  \"asset\": {{ \"version\": \"2.0\" }},\n  \"scene\": 0,\n  {corps},\n  \
             \"accessors\": [{}],\n  \"bufferViews\": [{}],\n  \
             \"buffers\": [{{ \"byteLength\": {}, \"uri\": \"{DATA_PREFIX}{}\" }}]\n}}",
            self.accesseurs.join(",\n    "),
            self.vues.join(",\n    "),
            self.bin.len(),
            encode(&self.bin)
        )
    }

    /// Un triangle au plan `z = 0`, positions puis indices : le mesh d'un node rigide.
    pub fn triangle(&mut self) -> (usize, usize) {
        let positions = self.flottants(&[0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0], "VEC3");
        let indices = self.indices(&[0, 1, 2]);
        (positions, indices)
    }
}

/// Encode en base64 standard, avec remplissage.
fn encode(octets: &[u8]) -> String {
    let mut out = String::new();
    for morceau in octets.chunks(3) {
        let mut bloc = [0u8; 3];
        bloc[..morceau.len()].copy_from_slice(morceau);
        let valeur = (u32::from(bloc[0]) << 16) | (u32::from(bloc[1]) << 8) | u32::from(bloc[2]);
        for decalage in [18, 12, 6, 0].into_iter().take(morceau.len() + 1) {
            out.push(ALPHABET[((valeur >> decalage) & 0x3F) as usize] as char);
        }
        for _ in 0..3 - morceau.len() {
            out.push('=');
        }
    }
    out
}

/// La matrice de liaison inverse d'un os posé à `(0, y, 0)` : une translation de `-y`, colonne par
/// colonne.
pub fn liaison_inverse(y: f32) -> [f32; 16] {
    [
        1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, -y, 0.0, 1.0,
    ]
}

/// Un bras à deux os : `epaule` à l'origine, `coude` un bloc au-dessus. La peau est une bande de
/// deux blocs de haut : la rangée du bas suit l'épaule, celle du milieu les deux à moitié, celle
/// du haut le coude. Le clip `plier` tourne le coude d'un quart de tour autour de `z`, en une
/// seconde, linéairement.
///
/// `joints` donne les os du skin dans l'ordre glTF (nodes 1 = epaule, 2 = coude) ; les indices des
/// sommets sont locaux à cet ordre.
pub fn bras_avec(joints: [usize; 2], liaisons: [[f32; 16]; 2]) -> String {
    let mut modele = Modele::default();
    let positions = modele.flottants(
        &[
            -0.1, 0.0, 0.0, 0.1, 0.0, 0.0, -0.1, 1.0, 0.0, 0.1, 1.0, 0.0, -0.1, 2.0, 0.0, 0.1, 2.0,
            0.0,
        ],
        "VEC3",
    );
    let indices = modele.indices(&[0, 1, 3, 0, 3, 2, 2, 3, 5, 2, 5, 4]);
    // Rang local de l'épaule et du coude dans le skin.
    let epaule = joints.iter().position(|node| *node == 1).expect("épaule") as u8;
    let coude = joints.iter().position(|node| *node == 2).expect("coude") as u8;
    let os = modele.joints(&[
        [epaule, 0, 0, 0],
        [epaule, 0, 0, 0],
        [epaule, coude, 0, 0],
        [epaule, coude, 0, 0],
        [coude, 0, 0, 0],
        [coude, 0, 0, 0],
    ]);
    let poids = modele.flottants(
        &[
            1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0, 1.0,
            0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0,
        ],
        "VEC4",
    );
    let matrices: Vec<f32> = liaisons.iter().flatten().copied().collect();
    let liaison = modele.flottants(&matrices, "MAT4");
    let temps = modele.flottants(&[0.0, 1.0], "SCALAR");
    let rotations = modele.flottants(
        &[0.0, 0.0, 0.0, 1.0, 0.0, 0.0, DEMI_RACINE, DEMI_RACINE],
        "VEC4",
    );
    modele.document(&format!(
        "\"scenes\": [{{ \"nodes\": [0, 1] }}],
  \"nodes\": [
    {{ \"name\": \"bras\", \"mesh\": 0, \"skin\": 0 }},
    {{ \"name\": \"epaule\", \"children\": [2] }},
    {{ \"name\": \"coude\", \"translation\": [0.0, 1.0, 0.0] }}
  ],
  \"meshes\": [{{ \"name\": \"peau\", \"primitives\": [{{
    \"attributes\": {{ \"POSITION\": {positions}, \"JOINTS_0\": {os}, \"WEIGHTS_0\": {poids} }},
    \"indices\": {indices} }}] }}],
  \"skins\": [{{ \"joints\": [{}, {}], \"inverseBindMatrices\": {liaison} }}],
  \"animations\": [{{ \"name\": \"plier\",
    \"samplers\": [{{ \"input\": {temps}, \"output\": {rotations}, \"interpolation\": \"LINEAR\" }}],
    \"channels\": [{{ \"sampler\": 0, \"target\": {{ \"node\": 2, \"path\": \"rotation\" }} }}] }}]",
        joints[0], joints[1]
    ))
}

/// Le bras, os dans l'ordre de la hiérarchie et liés à leur pose de repos.
pub fn bras() -> String {
    bras_avec([1, 2], [liaison_inverse(0.0), liaison_inverse(1.0)])
}

/// Un éventail de `n` os (au plus 128, la limite de `SKEL`) : l'os 0 à l'origine, les autres ses enfants,
/// l'os `k` à `(0, 0,1·k, 0)` — deux niveaux, sous la profondeur maximale d'une hiérarchie. L'os `k` est lié
/// par `T(0,01·k, −0,1·k, 0)`, une matrice qui lui est propre : au repos, sa palette est `T(0,01·k, 0, 0)`.
/// Un triangle par os le suit de tout son poids. Le clip `tourner` tourne l'os 0 d'un quart de tour autour
/// de `z`, en une seconde, linéairement.
pub fn eventail(n: usize) -> String {
    assert!((1..=128).contains(&n), "{n} os");
    let mut modele = Modele::default();
    let mut positions = Vec::new();
    let mut os = Vec::new();
    let mut poids = Vec::new();
    let mut indices = Vec::new();
    for k in 0..n {
        let y = 0.1 * k as f32;
        positions.extend_from_slice(&[0.0, y, 0.0, 0.05, y, 0.0, 0.0, y + 0.05, 0.0]);
        os.extend_from_slice(&[[k as u8, 0, 0, 0]; 3]);
        poids.extend_from_slice(&[1.0, 0.0, 0.0, 0.0].repeat(3));
        let premier = (3 * k) as u16;
        indices.extend_from_slice(&[premier, premier + 1, premier + 2]);
    }
    let positions = modele.flottants(&positions, "VEC3");
    let os = modele.joints(&os);
    let poids = modele.flottants(&poids, "VEC4");
    let indices = modele.indices(&indices);
    let liaisons: Vec<f32> = (0..n)
        .flat_map(|k| {
            let mut m = liaison_inverse(0.1 * k as f32);
            m[12] = 0.01 * k as f32;
            m
        })
        .collect();
    let liaison = modele.flottants(&liaisons, "MAT4");
    let temps = modele.flottants(&[0.0, 1.0], "SCALAR");
    let rotations = modele.flottants(
        &[0.0, 0.0, 0.0, 1.0, 0.0, 0.0, DEMI_RACINE, DEMI_RACINE],
        "VEC4",
    );
    // Le node 0 porte la peau ; les os sont les nodes 1 à n, les nodes 2 à n enfants du node 1.
    let mut noeuds = vec!["{ \"name\": \"peau\", \"mesh\": 0, \"skin\": 0 }".to_owned()];
    let enfants: Vec<String> = (2..=n).map(|node| node.to_string()).collect();
    noeuds.push(format!(
        "{{ \"name\": \"os_0\", \"children\": [{}] }}",
        enfants.join(", ")
    ));
    for k in 1..n {
        noeuds.push(format!(
            "{{ \"name\": \"os_{k}\", \"translation\": [0.0, {:?}, 0.0] }}",
            0.1 * k as f32
        ));
    }
    let joints: Vec<String> = (1..=n).map(|node| node.to_string()).collect();
    modele.document(&format!(
        "\"scenes\": [{{ \"nodes\": [0, 1] }}],
  \"nodes\": [
    {}
  ],
  \"meshes\": [{{ \"name\": \"peau\", \"primitives\": [{{
    \"attributes\": {{ \"POSITION\": {positions}, \"JOINTS_0\": {os}, \"WEIGHTS_0\": {poids} }},
    \"indices\": {indices} }}] }}],
  \"skins\": [{{ \"joints\": [{}], \"inverseBindMatrices\": {liaison} }}],
  \"animations\": [{{ \"name\": \"tourner\",
    \"samplers\": [{{ \"input\": {temps}, \"output\": {rotations}, \"interpolation\": \"LINEAR\" }}],
    \"channels\": [{{ \"sampler\": 0, \"target\": {{ \"node\": 1, \"path\": \"rotation\" }} }}] }}]",
        noeuds.join(",\n    "),
        joints.join(", ")
    ))
}

/// Un pendule de node : le clip `balancer` déplace le node en palier (0, puis 1 bloc en `x` à
/// 0,5 s, puis 0 à 1 s) et le tourne d'un quart de tour autour de `z`, linéairement, sur 0,8 s.
pub fn pendule() -> String {
    let mut modele = Modele::default();
    let (positions, indices) = modele.triangle();
    let temps_pas = modele.flottants(&[0.0, 0.5, 1.0], "SCALAR");
    let translations = modele.flottants(&[0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0], "VEC3");
    let temps_rotation = modele.flottants(&[0.0, 0.8], "SCALAR");
    let rotations = modele.flottants(
        &[0.0, 0.0, 0.0, 1.0, 0.0, 0.0, DEMI_RACINE, DEMI_RACINE],
        "VEC4",
    );
    modele.document(&format!(
        "\"scenes\": [{{ \"nodes\": [0] }}],
  \"nodes\": [{{ \"name\": \"pendule\", \"mesh\": 0 }}],
  \"meshes\": [{{ \"primitives\": [{{ \"attributes\": {{ \"POSITION\": {positions} }},
    \"indices\": {indices} }}] }}],
  \"animations\": [{{ \"name\": \"balancer\",
    \"samplers\": [
      {{ \"input\": {temps_pas}, \"output\": {translations}, \"interpolation\": \"STEP\" }},
      {{ \"input\": {temps_rotation}, \"output\": {rotations} }}
    ],
    \"channels\": [
      {{ \"sampler\": 0, \"target\": {{ \"node\": 0, \"path\": \"translation\" }} }},
      {{ \"sampler\": 1, \"target\": {{ \"node\": 0, \"path\": \"rotation\" }} }}
    ] }}]"
    ))
}

/// Un rebond en `CUBIC_SPLINE` : la balle part de 0 avec une vitesse de 4 blocs/s vers le haut et
/// revient à 0 en une seconde, à la vitesse opposée. Chaque clé porte sa tangente d'entrée, sa
/// valeur et sa tangente de sortie, dans cet ordre.
pub fn rebond() -> String {
    let mut modele = Modele::default();
    let (positions, indices) = modele.triangle();
    let temps = modele.flottants(&[0.0, 1.0], "SCALAR");
    let valeurs = modele.flottants(
        &[
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 4.0, 0.0, // clé 0
            0.0, -4.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, // clé 1
        ],
        "VEC3",
    );
    modele.document(&format!(
        "\"scenes\": [{{ \"nodes\": [0] }}],
  \"nodes\": [{{ \"name\": \"balle\", \"mesh\": 0 }}],
  \"meshes\": [{{ \"primitives\": [{{ \"attributes\": {{ \"POSITION\": {positions} }},
    \"indices\": {indices} }}] }}],
  \"animations\": [{{ \"name\": \"rebond\",
    \"samplers\": [{{ \"input\": {temps}, \"output\": {valeurs}, \"interpolation\": \"CUBICSPLINE\" }}],
    \"channels\": [{{ \"sampler\": 0, \"target\": {{ \"node\": 0, \"path\": \"translation\" }} }}] }}]"
    ))
}

/// Deux meshes, deux skins : le premier n'a que l'épaule, le second le coude puis l'épaule. Les
/// deux skins partagent l'épaule, liée par `liaison_epaule_second` dans le second. Chaque triangle
/// suit, de tout son poids, le joint local 0 de son skin.
pub fn deux_skins(liaison_epaule_second: [f32; 16]) -> String {
    let mut modele = Modele::default();
    let positions = modele.flottants(&[0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0], "VEC3");
    let indices = modele.indices(&[0, 1, 2]);
    let os = modele.joints(&[[0, 0, 0, 0]; 3]);
    let poids = modele.flottants(&[1.0, 0.0, 0.0, 0.0].repeat(3), "VEC4");
    let premier = modele.flottants(&liaison_inverse(0.0), "MAT4");
    let mut second: Vec<f32> = liaison_inverse(1.0).to_vec();
    second.extend_from_slice(&liaison_epaule_second);
    let second = modele.flottants(&second, "MAT4");
    modele.document(&format!(
        "\"scenes\": [{{ \"nodes\": [0, 1, 2] }}],
  \"nodes\": [
    {{ \"name\": \"peau_haute\", \"mesh\": 0, \"skin\": 0 }},
    {{ \"name\": \"peau_basse\", \"mesh\": 1, \"skin\": 1 }},
    {{ \"name\": \"epaule\", \"children\": [3] }},
    {{ \"name\": \"coude\", \"translation\": [0.0, 1.0, 0.0] }}
  ],
  \"meshes\": [
    {{ \"primitives\": [{{ \"attributes\": {{ \"POSITION\": {positions}, \"JOINTS_0\": {os},
      \"WEIGHTS_0\": {poids} }}, \"indices\": {indices} }}] }},
    {{ \"primitives\": [{{ \"attributes\": {{ \"POSITION\": {positions}, \"JOINTS_0\": {os},
      \"WEIGHTS_0\": {poids} }}, \"indices\": {indices} }}] }}
  ],
  \"skins\": [
    {{ \"joints\": [2], \"inverseBindMatrices\": {premier} }},
    {{ \"joints\": [3, 2], \"inverseBindMatrices\": {second} }}
  ]"
    ))
}
