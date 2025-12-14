// Active channels - extracted from channels.coffee.md
const channels = {
  // SomaFM streams
  '7soul': { tags: ['soma'] },
  'beatblender': { tags: ['soma'] },
  'bootliquor': { tags: ['soma'] },
  'bossa': { tags: ['soma', 'world'] },
  'brfm': { tags: ['soma', 'burningman'] },
  'chillits': { tags: ['soma'] },
  'christmas': { tags: ['soma'] },
  'cliqhop': { tags: ['soma', 'idm'] },
  'covers': { tags: ['soma'] },
  'darkzone': { tags: ['soma'] },
  'deepspaceone': { tags: ['soma'] },
  'defcon': { tags: ['soma'] },
  'deptstore': { tags: ['soma'] },
  'digitalis': { tags: ['soma', 'indie'] },
  'doomed': { tags: ['soma'] },
  'dronezone': { tags: ['soma'] },
  'dubstep': { tags: ['soma'] },
  'fluid': { tags: ['soma'] },
  'folkfwd': { tags: ['soma'] },
  'gsclassic': { tags: ['soma'] },
  'groovesalad': { tags: ['soma', 'indie'] },
  'illstreet': { tags: ['soma'] },
  'indiepop': { tags: ['soma', 'indie'] },
  'insound': { tags: ['soma'] },
  'jollysoul': { tags: ['soma'] },
  'live': { tags: ['soma'] },
  'lush': { tags: ['soma', 'female'] },
  'metal': { tags: ['soma'] },
  'missioncontrol': { tags: ['soma'] },
  'n5md': { tags: ['soma'] },
  'poptron': { tags: ['soma'] },
  'reggae': { tags: ['soma'] },
  'scanner': { tags: ['soma'] },
  'secretagent': { tags: ['soma'] },
  'seventies': { tags: ['soma'] },
  'sf1033': { tags: ['soma'] },
  'sfinsf': { tags: ['soma'] },
  'sonicuniverse': { tags: ['soma'] },
  'spacestation': { tags: ['soma'] },
  'specials': { tags: ['soma'] },
  'suburbsofgoa': { tags: ['soma', 'world'] },
  'synphaera': { tags: ['soma'] },
  'thetrip': { tags: ['soma'] },
  'thistle': { tags: ['soma'] },
  'tikitime': { tags: ['soma', 'world'] },
  'u80s': { tags: ['soma'] },
  'vaporwaves': { tags: ['soma'] },
  'xmasinfrisko': { tags: ['soma'] },
  'xmasrocks': { tags: ['soma'] },
  
  // Radio Paradise
  'paradise': {
    url: 'https://stream.radioparadise.com/aac-128',
    tags: ['paradise', 'US', 'rock', 'eclectic'],
    site: 'https://www.radioparadise.com/'
  },
  'paradise_mellow': {
    url: 'https://stream.radioparadise.com/mellow-128',
    tags: ['paradise', 'US', 'mellow', 'acoustic'],
    site: 'https://www.radioparadise.com/'
  },
  'paradise_rock': {
    url: 'https://stream.radioparadise.com/rock-128',
    tags: ['paradise', 'US', 'rock'],
    site: 'https://www.radioparadise.com/'
  },
  'paradise_global': {
    url: 'https://stream.radioparadise.com/global-128',
    tags: ['paradise', 'US', 'global', 'world'],
    site: 'https://www.radioparadise.com/'
  },
  'paradise_beyond': {
    url: 'https://stream.radioparadise.com/beyond-128',
    tags: ['paradise', 'US'],
    site: 'https://www.radioparadise.com/'
  },
  'paradise_serenity': {
    url: 'https://stream.radioparadise.com/serenity',
    tags: ['paradise', 'US', 'relaxation'],
    site: 'https://www.radioparadise.com/'
  },
  'paradise_2050': {
    url: 'https://stream.radioparadise.com/radio2050-128',
    tags: ['paradise', 'US', 'electronic', 'future'],
    site: 'https://www.radioparadise.com/'
  },

  // RTP - Portugal
  'antena1': {
    url: 'https://radiocast.rtp.pt/antena180a.mp3',
    tags: ['portugal', 'pt', 'public', 'news', 'variety'],
    site: 'https://www.rtp.pt/play/direto/antena1'
  },
  'antena2': {
    url: 'https://radiocast.rtp.pt/antena280a.mp3',
    tags: ['portugal', 'pt', 'public', 'classical', 'culture'],
    site: 'https://www.rtp.pt/play/direto/antena2'
  },
  'antena3': {
    url: 'https://radiocast.rtp.pt/antena380a.mp3',
    tags: ['portugal', 'pt', 'public', 'alternative', 'rock'],
    site: 'https://www.rtp.pt/play/direto/antena3'
  },

  // Concertzender - Netherlands
  'concertzender_live': {
    url: 'https://streams.greenhost.nl:8006/live',
    tags: ['concertzender', 'nl', 'classical', 'eclectic'],
    site: 'https://www.concertzender.nl/ontvangst/streamoverzicht/'
  },
  'concertzender_barok': {
    url: 'https://streams.greenhost.nl:8006/barok',
    tags: ['concertzender', 'nl', 'baroque', 'classical'],
    site: 'https://www.concertzender.nl/ontvangst/streamoverzicht/'
  },
  'concertzender_klassiek': {
    url: 'https://streams.greenhost.nl:8006/klassiek',
    tags: ['concertzender', 'nl', 'classical'],
    site: 'https://www.concertzender.nl/ontvangst/streamoverzicht/'
  },
  'concertzender_oudemuziek': {
    url: 'https://streams.greenhost.nl:8006/oudemuziek',
    tags: ['concertzender', 'nl', 'early', 'baroque'],
    site: 'https://www.concertzender.nl/ontvangst/streamoverzicht/'
  },
  'concertzender_jazz': {
    url: 'https://streams.greenhost.nl:8006/jazz',
    tags: ['concertzender', 'nl', 'jazz'],
    site: 'https://www.concertzender.nl/ontvangst/streamoverzicht/'
  },
  'concertzender_jazznotjazz': {
    url: 'https://streams.greenhost.nl:8006/jazznotjazz',
    tags: ['concertzender', 'nl', 'jazz', 'eclectic'],
    site: 'https://www.concertzender.nl/ontvangst/streamoverzicht/'
  },
  'concertzender_wereldmuziek': {
    url: 'https://streams.greenhost.nl:8006/wereldmuziek',
    tags: ['concertzender', 'nl', 'world'],
    site: 'https://www.concertzender.nl/ontvangst/streamoverzicht/'
  },
  
  // Other Stations
  'wassoulou': {
    url: 'https://listen.radionomy.com/radio-wassoulou-internationale',
    tags: ['other', 'mali', 'africa']
  },
  'meuh': {
    url: 'https://radiomeuh.ice.infomaniak.ch/radiomeuh-128.mp3',
    tags: ['other', 'fr', 'eclectic', 'paris'],
    site: 'https://www.radiomeuh.com/'
  },
  'ambientsleepingpill': {
    url: 'https://radio.stereoscenic.com/asp-s',
    tags: ['ambient', 'sleep', 'relaxation'],
    site: 'https://ambientsleepingpill.com/'
  },
  'amambient': {
    url: 'https://radio.stereoscenic.com/ama-s',
    tags: ['ambient', 'bright', 'daytime'],
    site: 'https://amambient.com/'
  },
  'ambientmodern': {
    url: 'https://radio.stereoscenic.com/mod-s',
    tags: ['ambient', 'modern'],
    site: 'https://ambientmodern.com/'
  },
  'urbanspaceradio': {
    url: 'https://stream.urbanspaceradio.com:8443/urban-space-radio',
    site: 'https://urbanspaceradio.com/',
    tags: ['other', 'ua', 'eclectic', 'chill']
  },
  'ledjam': {
    url: 'https://stream9.xdevel.com/audio1s976748-1515/stream/icecast.audio',
    tags: ['other', 'fr', 'eclectic'],
    site: 'https://www.djam.radio/'
  },
  'cumbiasinmortales': {
    url: 'https://panel.retrolandigital.com/listen/cumbias_inmortales_radio/listen',
    tags: ['mx', 'cumbia', 'latin'],
    site: 'https://cumbiasinmortales.com/'
  },
  
  // Cuban Music
  'radio_art_cuban': {
    url: 'https://live.radioart.com/fCuban_lounge.mp3',
    tags: ['cuban', 'cuba', 'latin', 'lounge'],
    site: 'https://www.radioart.com/'
  },
  'cubania_radio': {
    url: 'https://streaming.brol.tech/rtfmlounge',
    tags: ['cuban', 'cuba', 'latin'],
    site: 'https://radiosdecuba.com/cubania/'
  },
  'cubaradio80s': {
    url: 'https://a7.asurahosting.com:7670/radio.mp3',
    tags: ['cuban', 'cuba', 'latin', '80s'],
    site: 'https://www.cuba80s.com/radio'
  },
  // Salsa Stations
  'radio_pozo_de_la_salsa': {
    url: 'https://cast4.my-control-panel.com/proxy/elpozosalsa/;',
    tags: ['latin', 'salsa'],
    site: 'https://elpozodelasalsa.com/'
  },
  'world_salsa_radio': {
    url: 'https://s4.radio.co/sc6b77aa40/listen',
    tags: ['latin', 'salsa'],
    site: 'https://worldsalsaradio.com/'
  },
  '100_por_ciento_salsa': {
    url: 'https://stm01.streammaximum.com:8194/;',
    tags: ['latin', 'salsa'],
    site: 'https://www.100porcientosalsa.com/'
  },
  'zonasalsa': {
    url: 'https://cast6.asurahosting.com/proxy/salsafou/stream',
    tags: ['latin', 'salsa'],
    site: 'http://zonsalsaradio.com/'
  },
  
  // Bachata Stations
  'latina_bachata': {
    url: 'https://latinabachata.ice.infomaniak.ch/latinabachata.mp3',
    tags: ['latin', 'bachata'],
    site: 'https://www.latina.fr/'
  },
  'top_bachata_radio': {
    url: 'https://radio.dominiserver.com/proxy/topbachata?mp=/stream',
    tags: ['latin', 'bachata'],
    site: 'https://latina104.net/top-bachata-radio/'
  },
  
  // Country & Bluegrass
  'wsm_nashville': {
    url: 'https://stream01048.westreamradio.com/wsm-am-mp3',
    tags: ['country', 'classic', 'nashville', 'US'],
    site: 'https://wsmradio.com/'
  },
  'lautfm_country': {
    url: 'https://stream.laut.fm/country',
    tags: ['country', 'de'],
    site: 'https://laut.fm/country'
  },
  'lautfm_nashville': {
    url: 'https://stream.laut.fm/nashville',
    tags: ['country', 'de'],
    site: 'https://laut.fm/nashville'
  },
  'gotradio_bluegrass': {
    url: 'http://gr01.cdnstream.com:8490/',
    tags: ['bluegrass', 'country', 'US'],
    site: 'https://www.gotradio.com/'
  },
  
  // FIP - Radio France
  'fip': {
    url: 'https://icecast.radiofrance.fr/fip-midfi.mp3?id=radiofrance',
    tags: ['fr', 'fip', 'eclectic'],
    site: 'https://www.radiofrance.fr/fip'
  },
  'fip_jazz': {
    url: 'https://icecast.radiofrance.fr/fipjazz-midfi.mp3?id=radiofrance',
    tags: ['fr', 'fip', 'jazz'],
    site: 'https://www.radiofrance.fr/fip/radio-jazz'
  },
  'fip_rock': {
    url: 'https://icecast.radiofrance.fr/fiprock-midfi.mp3?id=radiofrance',
    tags: ['fr', 'fip', 'rock'],
    site: 'https://www.radiofrance.fr/fip/radio-rock'
  },
  'fip_groove': {
    url: 'https://icecast.radiofrance.fr/fipgroove-midfi.mp3?id=radiofrance',
    tags: ['fr', 'fip', 'groove', 'soul', 'funk'],
    site: 'https://www.radiofrance.fr/fip/radio-groove'
  },
  'fip_reggae': {
    url: 'https://icecast.radiofrance.fr/fipreggae-midfi.mp3?id=radiofrance',
    tags: ['fr', 'fip', 'reggae'],
    site: 'https://www.radiofrance.fr/fip/radio-reggae'
  },
  'fip_pop': {
    url: 'https://icecast.radiofrance.fr/fippop-midfi.mp3?id=radiofrance',
    tags: ['fr', 'fip', 'pop'],
    site: 'https://www.radiofrance.fr/fip/radio-pop'
  },
  'fip_electro': {
    url: 'https://icecast.radiofrance.fr/fipelectro-midfi.mp3?id=radiofrance',
    tags: ['fr', 'fip', 'electro', 'electronic'],
    site: 'https://www.radiofrance.fr/fip/radio-electro'
  },
  'fip_monde': {
    url: 'https://icecast.radiofrance.fr/fipworld-midfi.mp3?id=radiofrance',
    tags: ['fr', 'fip', 'world'],
    site: 'https://www.radiofrance.fr/fip/radio-monde'
  },
  'fip_nouveautes': {
    url: 'https://icecast.radiofrance.fr/fipnouveautes-midfi.mp3?id=radiofrance',
    tags: ['fr', 'fip', 'nouveautes'],
    site: 'https://www.radiofrance.fr/fip/radio-nouveautes'
  },
  'fip_metal': {
    url: 'https://icecast.radiofrance.fr/fipmetal-midfi.mp3?id=radiofrance',
    tags: ['fr', 'fip', 'metal'],
    site: 'https://www.radiofrance.fr/fip/radio-metal'
  },
  'fip_hiphop': {
    url: 'https://icecast.radiofrance.fr/fiphiphop-midfi.mp3?id=radiofrance',
    tags: ['fr', 'fip', 'hiphop', 'hip-hop'],
    site: 'https://www.radiofrance.fr/fip/radio-hip-hop'
  },
  'fip_sacre_francais': {
    url: 'https://icecast.radiofrance.fr/fipsacrefrancais-midfi.mp3?id=radiofrance',
    tags: ['fr', 'fip', 'francais', 'french'],
    site: 'https://www.radiofrance.fr/fip/radio-sacre-francais'
  },
  
  // Flux FM - Berlin
  'flux_fm_techno_underground': {
    url: 'https://channels.fluxfm.de/techno-underground/externalembedflxhp/stream.mp3',
    tags: ['flux', 'techno', 'underground', 'electronic', 'berlin', 'de'],
    site: 'https://www.fluxfm.de/techno-underground'
  },
  'flux_fm_boomfm': {
    url: 'https://streams.fluxfm.de/boomfm/mp3-320/audio/',
    tags: ['flux', 'hiphop', 'rap', 'urban', 'berlin', 'de'],
    site: 'https://www.fluxfm.de/boomfm'
  },
  'flux_fm_boomfm_classics': {
    url: 'https://streams.fluxfm.de/boomfmclassics/mp3-320/audio/',
    tags: ['flux', 'hiphop', 'rap', 'classics', 'berlin', 'de'],
    site: 'https://www.fluxfm.de/boomfm'
  },
  'flux_fm_elektroflux': {
    url: 'https://streams.fluxfm.de/elektro/mp3-320/audio/',
    tags: ['flux', 'electronic', 'indie', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_clubsandwich': {
    url: 'https://streams.fluxfm.de/clubsandwich/mp3-320/audio/',
    tags: ['flux', 'electronic', 'house', 'techno', 'berlin', 'de'],
    site: 'https://www.fluxfm.de/clubsandwich'
  },
  'flux_fm_sound_of_berlin': {
    url: 'https://streams.fluxfm.de/soundofberlin/mp3-320/audio/',
    tags: ['flux', 'electronic', 'techno', 'berlin', 'de'],
    site: 'https://www.fluxfm.de/sound-of-berlin'
  },
  'flux_fm_berlin_beach_house': {
    url: 'https://streams.fluxfm.de/bbeachhouse/mp3-320/audio/',
    tags: ['flux', 'electronic', 'house', 'chill', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_john_reed': {
    url: 'https://streams.fluxfm.de/john-reed/mp3-320/audio/',
    tags: ['flux', 'electronic', 'hiphop', 'techno', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_klubradio': {
    url: 'https://streams.fluxfm.de/klubradio/mp3-320/audio/',
    tags: ['flux', 'dance', 'club', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_chillhop': {
    url: 'https://streams.fluxfm.de/Chillhop/mp3-320/streams.fluxfm.de/',
    tags: ['flux', 'lofi', 'chillhop', 'beats', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_fluxforward': {
    url: 'https://streams.fluxfm.de/forward/mp3-320/audio/',
    tags: ['flux', 'indie', 'alternative', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_fluxkompensator': {
    url: 'https://streams.fluxfm.de/fluxkompensator/mp3-320/audio/',
    tags: ['flux', 'alternative', 'indie', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_lounge': {
    url: 'https://streams.fluxfm.de/lounge/mp3-320/audio/',
    tags: ['flux', 'chill', 'lounge', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_hippie_trippy_garden_pretty': {
    url: 'https://streams.fluxfm.de/event02/mp3-320/radiode/',
    tags: ['flux', 'psychedelic', 'electronic', 'experimental', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_rasta_radio': {
    url: 'https://streams.fluxfm.de/rastaradio/mp3-320/streams.fluxfm.de/',
    tags: ['flux', 'reggae', 'dub', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_dub_radio': {
    url: 'https://streams.fluxfm.de/dubradio/mp3-320/streams.fluxfm.de/',
    tags: ['flux', 'dub', 'reggae', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_fluxrap': {
    url: 'https://streams.fluxfm.de/rap/mp3-320/streams.fluxfm.de/',
    tags: ['flux', 'rap', 'hiphop', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_metal': {
    url: 'https://streams.fluxfm.de/metalfm/mp3-320/radiode/',
    tags: ['flux', 'metal', 'hardrock', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_hard_rock': {
    url: 'https://streams.fluxfm.de/hardrock/mp3-320/streams.fluxfm.de/',
    tags: ['flux', 'hardrock', 'rock', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_b_funk': {
    url: 'https://streams.fluxfm.de/event01/mp3-320/streams.fluxfm.de/',
    tags: ['flux', 'funk', 'soul', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_hot_rnb': {
    url: 'https://streams.fluxfm.de/rnb/mp3-320/streams.fluxfm.de/',
    tags: ['flux', 'rnb', 'soul', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_jazzradio_schwarzenstein': {
    url: 'https://streams.fluxfm.de/jazzschwarz/mp3-320/audio/',
    tags: ['flux', 'jazz', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_xjazz': {
    url: 'https://streams.fluxfm.de/xjazz/mp3-320/audio/',
    tags: ['flux', 'jazz', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_neofm': {
    url: 'https://streams.fluxfm.de/neofm/mp3-320/radiode/',
    tags: ['flux', 'neoclassical', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_jaegermusic': {
    url: 'https://streams.fluxfm.de/studio56/mp3-320/audio/',
    tags: ['flux', 'electronic', 'indie', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_70er': {
    url: 'https://streams.fluxfm.de/70er/mp3-320/audio/',
    tags: ['flux', '70s', 'classic', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_80er': {
    url: 'https://streams.fluxfm.de/80er/mp3-320/streams.fluxfm.de/',
    tags: ['flux', '80s', 'classic', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  'flux_fm_60er': {
    url: 'https://streams.fluxfm.de/60er/mp3-320/streams.fluxfm.de/',
    tags: ['flux', '60s', 'classic', 'berlin', 'de'],
    site: 'https://www.fluxfm.de'
  },
  
  // RadioJAZZ.FM
  // 'radiojazz': {
  //   url: 'http://radiojazz.fm:8000/',
  //   tags: ['jazz', 'poland'],
  //   site: 'https://radiojazz.fm/'
  // },
  
  // Radio Zinzine
  // 'radiozinzine': {
  //   url: 'http://live.francra.org:8000/radiozinzine',
  //   tags: ['fr', 'associative', 'alternative'],
  //   site: 'https://www.radiozinzine.org/'
  // },
  
  // Radio Grenouille
  // 'radiogrenouille': {
  //   url: 'http://live.francra.org:8000/radiogrenouille',
  //   tags: ['fr', 'marseille', 'cultural'],
  //   site: 'https://www.radiogrenouille.com/'
  // },
  
  // Radio Canut
  // 'radiocanut': {
  //   url: 'http://live.francra.org:8000/radiocanut',
  //   tags: ['fr', 'lyon', 'alternative'],
  //   site: 'https://www.radiocanut.org/'
  // },
  
  // Radio Libertaire
  // 'radiolibertaire': {
  //   url: 'http://live.francra.org:8000/radiolibertaire',
  //   tags: ['fr', 'paris', 'anarchist'],
  //   site: 'https://www.radio-libertaire.net/'
  // },
  
  // Radio Campus Lille
  // 'radiocampuslille': {
  //   url: 'http://live.francra.org:8000/radiocampuslille',
  //   tags: ['fr', 'lille', 'student'],
  //   site: 'https://www.campuslille.com/'
  // },
  
  // Couleur 3
  // 'couleur3': {
  //   url: 'https://stream.srg-ssr.ch/m/couleur3/mp3_128',
  //   tags: ['ch', 'switzerland', 'alternative'],
  //   site: 'https://www.rts.ch/couleur3/'
  // },
  
  // Neringa FM
  // 'neringa': {
  //   url: 'http://streamer.midiaudio.com:80/neringa',
  //   tags: ['lt', 'eclectic', 'chill'],
  //   site: 'https://www.neringafm.lt/'
  // },
  
  // Vikerraadio
  // 'vikerraadio': {
  //   url: 'https://icecast.err.ee/vikerraadio',
  //   tags: ['ee', 'estonia', 'news'],
  //   site: 'https://vikerraadio.err.ee/'
  // }
};

// Channel sections configuration
// Each section defines:
//   - name: Display name for the section
//   - tags: Array of tags to filter channels (channel must have at least one matching tag)
//   - order: Display order (lower numbers appear first)
const sections = [
  {
    name: 'Other Stations',
    tags: ['other'],
    order: 8
  },
  {
    name: 'Country',
    tags: ['country'],
    order: 10
  },
  {
    name: 'Latin',
    tags: ['latin'],
    order: 9
  },
  {
    name: 'Radio Paradise',
    tags: ['paradise'],
    order: 7
  },
  {
    name: 'SomaFM',
    tags: ['soma'],
    order: 3
  },
  {
    name: 'FIP',
    tags: ['fip'],
    order: 4
  },
  {
    name: 'Flux FM',
    tags: ['flux'],
    order: 5
  },
  {
    name: 'Portugal',
    tags: ['portugal'],
    order: 2
  },
  {
    name: 'Concertzender',
    tags: ['concertzender'],
    order: 6
  },
  {
    name: 'AMBIENT',
    tags: ['ambient'],
    order: 1
  }
];

// Broken channels (for reference - commented out in channels.coffee.md)
// These channels failed connection tests and are kept here for potential future fixes
// const brokenChannels = {
  // 'chillstep.info': { url: 'http://chillstep.info:1984/listen.ogg', tags: ['dubstep', 'chill'] },
  // 'pmr': { url: 'http://pmr.lt/streams/pmr-2', tags: ['lt', 'eclectic', 'chill', 'world'], site: 'http://pmr.lt/en' },
  // 'neringa': { url: 'http://streamer.midiaudio.com:80/ner', tags: ['lt', 'eclectic', 'chill'], site: 'http://www.neringafm.lt/' },
  // 'bbcworld': { url: 'http://bbcwssc.ic.llnwd.net/stream/bbcwssc_mp1_ws-eieuk', tags: ['uk', 'news'] },
  // 'radiopanik': { url: 'http://streaming.domainepublic.net:8000/radiopanik.ogg', tags: ['libre', 'bxl', 'be'] },
  // 'radioairlibre': { url: 'http://streaming.domainepublic.net:8000/radioairlibre.ogg', tags: ['libre', 'bxl', 'be'] },
  // 'radiocampusbxl': { url: 'http://streamer.radiocampusbruxelles.org:8000/stream.ogg', tags: ['bxl', 'be'] },
  // 'couleur3': { url: 'http://stream.srg-ssr.ch/m/couleur3/mp3_128', tags: ['ch'] },
  // 'amazing': { url: 'http://109.74.195.10:8000', pls: 'http://stream.amazingradio.com:8000/listen.pls', site: 'http://amazingradio.com/' }
// };

