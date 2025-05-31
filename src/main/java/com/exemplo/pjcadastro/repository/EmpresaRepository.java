
package com.exemplo.pjcadastro.repository;

import com.exemplo.pjcadastro.model.Empresa;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmpresaRepository extends JpaRepository<Empresa, String> {
}
