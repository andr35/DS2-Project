export interface SSHKey {
  name: string,
  publicKey: string
}

export interface DropletResponse {
  droplets: Droplet[];
  links: Links;
  meta: Meta;
}

export interface Kernel {
  id: number;
  name: string;
  version: string;
}

export interface Image {
  id: number;
  name: string;
  distribution: string;
  slug: string;
  public: boolean;
  regions: string[];
  created_at: Date;
  type: string;
  min_disk_size: number;
  size_gigabytes: number;
}

export interface Size {
}

export interface V4 {
  ip_address: string;
  netmask: string;
  gateway: string;
  type: string;
}

export interface V6 {
  ip_address: string;
  netmask: number;
  gateway: string;
  type: string;
}

export interface Networks {
  v4: V4[];
  v6: V6[];
}

export interface Region {
  name: string;
  slug: string;
  sizes: any[];
  features: string[];
  available?: any;
}

export interface Droplet {
  id: number;
  name: string;
  memory: number;
  vcpus: number;
  disk: number;
  locked: boolean;
  status: string;
  kernel: Kernel;
  created_at: Date;
  features: string[];
  backup_ids: number[];
  snapshot_ids: any[];
  image: Image;
  volumes: any[];
  size: Size;
  size_slug: string;
  networks: Networks;
  region: Region;
  tags: any[];
}

export interface Pages {
  last: string;
  next: string;
}

export interface Links {
  pages: Pages;
}

export interface Meta {
  total: number;
}
